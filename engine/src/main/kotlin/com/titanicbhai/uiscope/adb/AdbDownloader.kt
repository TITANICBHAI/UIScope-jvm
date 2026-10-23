package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.HostOs
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.error.Remedy
import com.titanicbhai.uiscope.process.NetworkGateway
import com.titanicbhai.uiscope.storage.DataPaths
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What the user is asked to agree to before anything is downloaded (decision D3). */
data class DownloadConsent(val revision: String, val url: String, val sizeBytes: Long, val installDir: Path) {
    fun text(): String =
        "UIScope will download Google's official Android SDK Platform-Tools $revision (about ${(sizeBytes + MB - 1) / MB} MB) from $url " +
            "and install them in $installDir. They provide adb. Nothing else is downloaded or installed."

    private companion object {
        const val MB = 1024L * 1024L
    }
}

/**
 * Downloads Google's platform-tools for this operating system into the app's own folder:
 * pinned URL → SHA-256 check → safe extraction → check that adb is inside → atomic replace of the install folder.
 * Cancelling, or any failure, leaves no partial download and no half-installed folder.
 */
class AdbDownloader(
    private val gateway: NetworkGateway,
    private val dataPaths: DataPaths,
    private val manifest: PlatformToolsManifest,
    private val os: HostOs,
    private val dispatchers: DispatcherProvider,
    private val limits: ZipSafe.Limits = ZipSafe.Limits(),
    private val logger: Logger = Logger.getLogger("uiscope.adb"),
) {
    private val exe = if (os == HostOs.WINDOWS) "adb.exe" else "adb"

    private fun entry(): PlatformToolsEntry? = manifest.entries[os]

    fun consent(): Outcome<DownloadConsent> {
        val e = entry() ?: return unavailable()
        return Outcome.Success(DownloadConsent(manifest.revision, e.url, e.sizeBytes, dataPaths.platformTools))
    }

    /** Returns the path of the installed adb. */
    suspend fun install(onProgress: (Long, Long?) -> Unit = { _, _ -> }): Outcome<Path> {
        val e = entry() ?: return unavailable()
        dataPaths.ensureDirectories().let { if (it is Outcome.Failure) return it }
        val id = UUID.randomUUID().toString()
        val zip = dataPaths.tmp.resolve("platform-tools-${manifest.revision}-$id.zip.part")
        val extractDir = dataPaths.tmp.resolve("platform-tools-extract-$id")
        try {
            gateway.download(e.url, zip, 10.minutes, onProgress).let { if (it is Outcome.Failure) return it }
            withContext(dispatchers.io) {
                val context = currentCoroutineContext() // lets the extraction notice a cancel between files
                if (!sha256(zip).equals(e.sha256, ignoreCase = true)) {
                    Outcome.Failure(CodedError(ErrorCodes.ADB_DOWNLOAD_CHECKSUM, "The downloaded file does not match the expected checksum and was discarded", Remedy.RETRY))
                } else {
                    when (val x = ZipSafe.extract(zip, extractDir, limits) { context.ensureActive() }) {
                        is Outcome.Failure -> x
                        is Outcome.Success -> install(extractDir.resolve("platform-tools"))
                    }
                }
            }.let { if (it is Outcome.Failure) return it }
            return Outcome.Success(dataPaths.platformTools.resolve(exe))
        } finally {
            withContext(NonCancellable + dispatchers.io) {
                deleteQuietly(zip)
                deleteTreeQuietly(extractDir)
            }
        }
    }

    private fun install(extracted: Path): Outcome<Path> {
        if (!Files.isRegularFile(extracted.resolve(exe))) {
            return Outcome.Failure(CodedError(ErrorCodes.ADB_DOWNLOAD_INVALID_ARCHIVE, "The archive does not contain $exe", Remedy.RETRY))
        }
        val target = dataPaths.platformTools
        val backup = target.resolveSibling("platform-tools.old-${UUID.randomUUID()}")
        return try {
            if (os != HostOs.WINDOWS) markExecutable(extracted)
            Files.createDirectories(target.parent)
            val hadOld = Files.exists(target)
            if (hadOld) Files.move(target, backup)
            try {
                moveDirectory(extracted, target)
            } catch (e: IOException) {
                if (hadOld) Files.move(backup, target) // put the old install back
                throw e
            }
            deleteTreeQuietly(backup)
            Outcome.Success(target.resolve(exe))
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not install platform-tools", e)
            Outcome.Failure(CodedError(ErrorCodes.DATA_DIR_UNAVAILABLE, "Could not install the downloaded tools into $target", Remedy.RETRY))
        }
    }

    private fun moveDirectory(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            logger.log(Level.FINE, "atomic move not supported, using a plain move", e)
            Files.move(from, to)
        }
    }

    private fun markExecutable(dir: Path) {
        Files.list(dir).use { entries ->
            entries.filter { Files.isRegularFile(it) && !it.fileName.toString().contains('.') }.forEach {
                try {
                    Files.setPosixFilePermissions(it, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE))
                } catch (e: UnsupportedOperationException) {
                    logger.log(Level.FINE, "no POSIX permissions on this file system", e)
                }
            }
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { stream -> copyTo(stream) { digest.update(it.first, 0, it.second) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyTo(stream: InputStream, sink: (Pair<ByteArray, Int>) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            sink(buffer to n)
        }
    }

    private fun unavailable() = Outcome.Failure(
        CodedError(ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE, "This build has no adb download for this system. Install Android platform-tools yourself and choose adb.", Remedy.RETRY),
    )

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not delete a temporary file", e)
        }
    }

    private fun deleteTreeQuietly(dir: Path) {
        if (!Files.exists(dir)) return
        try {
            Files.walk(dir).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not delete a temporary folder", e)
        }
    }
}

/** Extracts a zip without trusting it: no path escapes the destination, and size and entry-count limits stop zip bombs. */
object ZipSafe {
    data class Limits(val maxEntries: Int = 5_000, val maxTotalBytes: Long = 500L * 1024 * 1024)

    /** False for anything that could land outside the destination: parent segments, absolute paths, drive letters, NUL, backslash tricks. */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.contains('\u0000')) return false
        if (name.startsWith("/") || name.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(name)) return false
        return name.split('/', '\\').none { it == ".." }
    }

    fun extract(zip: Path, destination: Path, limits: Limits = Limits(), checkActive: () -> Unit = {}): Outcome<Unit> {
        val root = destination.toAbsolutePath().normalize()
        return try {
            Files.createDirectories(root)
            ZipFile(zip.toFile()).use { file ->
                if (file.size() > limits.maxEntries) return invalid("The archive has too many files (${file.size()})")
                var total = 0L
                for (entry in file.entries()) {
                    checkActive()
                    if (!isSafeEntryName(entry.name)) return invalid("The archive contains an unsafe path")
                    val out = root.resolve(entry.name.replace('\\', '/')).normalize()
                    if (!out.startsWith(root)) return invalid("The archive contains an unsafe path")
                    if (entry.isDirectory) {
                        Files.createDirectories(out)
                        continue
                    }
                    Files.createDirectories(out.parent)
                    file.getInputStream(entry).use { input ->
                        Files.newOutputStream(out).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > limits.maxTotalBytes) return invalid("The archive expands to more than ${limits.maxTotalBytes / (1024 * 1024)} MB")
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                }
            }
            Outcome.Success(Unit)
        } catch (e: ZipException) {
            invalid("The archive is damaged (${e.javaClass.simpleName})")
        } catch (e: IOException) {
            Outcome.Failure(CodedError(ErrorCodes.DATA_DIR_UNAVAILABLE, "The archive could not be extracted (${e.javaClass.simpleName})", Remedy.RETRY))
        }
    }

    private fun invalid(detail: String) = Outcome.Failure(CodedError(ErrorCodes.ADB_DOWNLOAD_INVALID_ARCHIVE, detail, Remedy.RETRY))
}
