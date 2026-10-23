package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.HostOs
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.error.Remedy
import com.titanicbhai.uiscope.process.ProcessRunner
import com.titanicbhai.uiscope.storage.DataPaths
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.seconds

enum class AdbSource { SAVED, SDK_ENV, SDK_DEFAULT, PATH, MANAGED }

/** The adb that will be used, and where it came from, so the UI can say "Using adb from …". */
data class AdbLocation(val path: Path, val version: AdbVersion, val source: AdbSource)

/** Remembers the adb the user chose or the app installed. Backed by the settings file once P1-6 exists. */
interface AdbPathStore {
    fun load(): String?

    fun save(path: String)
}

/** A one-line text file; written through a temporary file so a crash never leaves half a path. */
class FileAdbPathStore(private val file: Path, private val logger: Logger = Logger.getLogger("uiscope.adb")) : AdbPathStore {
    override fun load(): String? =
        try {
            if (Files.isRegularFile(file)) Files.readString(file).trim().ifEmpty { null } else null
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not read the saved adb path", e)
            null
        }

    override fun save(path: String) {
        try {
            Files.createDirectories(file.toAbsolutePath().parent)
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(temp, path)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not save the adb path", e)
        }
    }
}

/**
 * Finds a working adb. Existing installations come first and the managed download is the last resort (decision D3):
 * 1. the saved path, 2. `ANDROID_SDK_ROOT` / `ANDROID_HOME`, 3. the default SDK folder, 4. `PATH`, 5. the app's own copy.
 * A candidate counts only if it is a file and `adb version` runs and prints adb's version banner.
 */
class AdbLocator(
    private val runner: ProcessRunner,
    private val store: AdbPathStore,
    private val dataPaths: DataPaths,
    private val os: HostOs,
    private val env: Map<String, String>,
    private val home: Path,
    private val pathSeparator: String = File.pathSeparator,
    private val logger: Logger = Logger.getLogger("uiscope.adb"),
) {
    private val exe = if (os == HostOs.WINDOWS) "adb.exe" else "adb"

    suspend fun locate(): Outcome<AdbLocation> {
        var tried = 0
        for ((source, path) in candidates()) {
            tried++
            validate(path, source)?.let { return Outcome.Success(it) }
        }
        return Outcome.Failure(CodedError(ErrorCodes.ADB_NOT_FOUND, "No working adb found ($tried places checked)", Remedy.RETRY))
    }

    /** The user pointed at a file ("Browse…"): accept it only if it works, and remember it. */
    suspend fun select(path: Path): Outcome<AdbLocation> {
        val location = validate(path, AdbSource.SAVED)
            ?: return Outcome.Failure(CodedError(ErrorCodes.ADB_NOT_FOUND, "That file is not a working adb", Remedy.RETRY))
        store.save(path.toString())
        return Outcome.Success(location)
    }

    private fun candidates(): List<Pair<AdbSource, Path>> {
        val out = ArrayList<Pair<AdbSource, Path>>()
        store.load()?.let { out += AdbSource.SAVED to Path.of(it) }
        for (key in listOf("ANDROID_SDK_ROOT", "ANDROID_HOME")) {
            env[key]?.takeIf { it.isNotBlank() }?.let { out += AdbSource.SDK_ENV to Path.of(it).resolve("platform-tools").resolve(exe) }
        }
        out += AdbSource.SDK_DEFAULT to defaultSdk().resolve("platform-tools").resolve(exe)
        val pathValue = env.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value.orEmpty()
        pathValue.split(pathSeparator).filter { it.isNotBlank() }.forEach { out += AdbSource.PATH to Path.of(it).resolve(exe) }
        out += AdbSource.MANAGED to dataPaths.platformTools.resolve(exe)
        return out.distinctBy { it.second.toAbsolutePath().normalize() }
    }

    private fun defaultSdk(): Path = when (os) {
        HostOs.WINDOWS -> (env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: home.resolve("AppData").resolve("Local")).resolve("Android").resolve("Sdk")
        HostOs.MACOS -> home.resolve("Library").resolve("Android").resolve("sdk")
        HostOs.LINUX, HostOs.OTHER -> home.resolve("Android").resolve("Sdk")
    }

    private suspend fun validate(path: Path, source: AdbSource): AdbLocation? {
        if (!Files.isRegularFile(path)) return null
        val result = runner.run(listOf(path.toString(), "version"), 5.seconds)
        val ok = (result as? Outcome.Success)?.value ?: return null
        if (ok.exitCode != 0) return null
        val version = AdbParsers.parseVersion(ok.stdoutText()) ?: return null
        logger.info("adb found: source=$source version=$version")
        return AdbLocation(path, version, source)
    }
}
