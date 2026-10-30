package com.titanicbhai.uiscope.storage

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.Screenshot
import com.titanicbhai.uiscope.snapshot.BundleCodec
import com.titanicbhai.uiscope.snapshot.SnapshotMeta
import com.titanicbhai.uiscope.snapshot.SnapshotStore
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.withContext

/**
 * Export and import of `.uiscope` bundles on real files (plan §4.7). The format and its validation live in
 * [BundleCodec]; this class only moves bytes between the store and the user's chosen paths.
 *
 * Import never uses a name from inside the bundle as a path: the screenshot is written to a temporary file chosen here, handed to the
 * store (which copies it into the snapshot's own folder) and deleted again.
 */
class SnapshotBundles(
    private val store: SnapshotStore,
    private val dispatchers: DispatcherProvider,
    private val tempDir: Path,
    private val appVersion: String,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: Logger = Logger.getLogger("uiscope.bundles"),
) {
    /** Writes snapshot [id] to [target] (replacing it) via a temporary file next to it, so a failed export never leaves half a file. */
    suspend fun export(id: String, target: Path): Outcome<Unit> = withContext(dispatchers.io) {
        val loaded = when (val l = store.load(id)) {
            is Outcome.Failure -> return@withContext l
            is Outcome.Success -> l.value
        }
        val temp = target.resolveSibling(target.fileName.toString() + ".part")
        try {
            val png = loaded.screenshotPath?.let { Files.readAllBytes(it) }
            val written = Files.newOutputStream(temp).use { out ->
                BundleCodec.write(out, loaded.capture, png, appVersion, clock.instant(), loaded.meta.title, loaded.meta.note)
            }
            if (written is Outcome.Failure) {
                deleteQuietly(temp)
                return@withContext written
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                logger.log(Level.FINE, "atomic move not supported, using a plain move", e)
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            Outcome.Success(Unit)
        } catch (e: IOException) {
            logger.log(Level.WARNING, "bundle export failed", e)
            deleteQuietly(temp)
            Outcome.Failure(CodedError(ErrorCodes.EXPORT_FAILED, "Could not write the file (${e.javaClass.simpleName}). Check that the folder exists and has free space."))
        }
    }

    /** Validates [source] and saves it as a new snapshot. */
    suspend fun import(source: Path): Outcome<SnapshotMeta> = withContext(dispatchers.io) {
        val size = try {
            Files.size(source)
        } catch (e: IOException) {
            logger.log(Level.WARNING, "bundle import: cannot read the file", e)
            return@withContext Outcome.Failure(CodedError(ErrorCodes.SNAPSHOT_IMPORT_INVALID, "That file can't be imported. It could not be opened."))
        }
        if (size > BundleCodec.MAX_BYTES) {
            return@withContext Outcome.Failure(CodedError(ErrorCodes.SNAPSHOT_IMPORT_INVALID, "That file can't be imported. It is larger than ${BundleCodec.MAX_BYTES / (1024 * 1024)} MB."))
        }
        val bundle = try {
            Files.newInputStream(source).use { BundleCodec.read(it.buffered()) }
        } catch (e: IOException) {
            logger.log(Level.WARNING, "bundle import: read failed", e)
            return@withContext Outcome.Failure(CodedError(ErrorCodes.SNAPSHOT_IMPORT_INVALID, "That file can't be imported. It could not be read."))
        }.let {
            when (it) {
                is Outcome.Failure -> return@withContext it
                is Outcome.Success -> it.value
            }
        }
        var temp: Path? = null
        try {
            var capture = bundle.capture
            val png = bundle.screenshotPng
            if (png != null) {
                Files.createDirectories(tempDir)
                val file = tempDir.resolve("import-" + UUID.randomUUID() + ".png")
                temp = file
                Files.write(file, png)
                capture = capture.copy(screenshot = Screenshot(file.toString(), capture.screenshot?.width ?: 0, capture.screenshot?.height ?: 0))
            }
            store.save(capture, bundle.manifest.title, bundle.manifest.note.orEmpty())
        } catch (e: IOException) {
            logger.log(Level.WARNING, "bundle import: could not stage the screenshot", e)
            Outcome.Failure(CodedError(ErrorCodes.SNAPSHOT_SAVE_FAILED, "Could not save the imported snapshot (${e.javaClass.simpleName})."))
        } finally {
            temp?.let { deleteQuietly(it) }
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not delete a temporary file", e)
        }
    }
}
