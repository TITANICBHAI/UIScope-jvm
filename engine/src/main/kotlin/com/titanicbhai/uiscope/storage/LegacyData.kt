package com.titanicbhai.uiscope.storage

import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The v1.0.0-era data folder `~/.uiscope` (plan §3.5): old sessions are **not** migrated; the downloaded platform-tools are moved once
 * so users don't download them again, and the rest can be deleted after the user agrees.
 */
class LegacyData(
    private val legacyRoot: Path,
    private val paths: DataPaths,
    private val logger: Logger = Logger.getLogger("uiscope.legacy"),
) {
    private val legacyTools: Path = legacyRoot.resolve("platform-tools")

    /**
     * Moves `~/.uiscope/platform-tools` to the new tools folder when it holds adb and the new folder holds none. Returns true when it
     * moved something. Safe to call at every start: after the move the source is gone.
     */
    fun movePlatformTools(): Outcome<Boolean> {
        if (!Files.isDirectory(legacyTools) || !holdsAdb(legacyTools) || holdsAdb(paths.platformTools)) return Outcome.Success(false)
        return try {
            if (Files.isDirectory(paths.platformTools)) deleteEmptyTree(paths.platformTools)
            Files.createDirectories(paths.platformTools.parent)
            try {
                Files.move(legacyTools, paths.platformTools, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                logger.log(Level.FINE, "atomic move not supported (another drive?), copying instead", e)
                copyTree(legacyTools, paths.platformTools)
                deleteTree(legacyTools)
            }
            logger.info("moved the old platform-tools folder to the new tools folder")
            Outcome.Success(true)
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not move the old platform-tools", e)
            Outcome.Failure(CodedError(ErrorCodes.DATA_DIR_UNAVAILABLE, "Could not move the old platform-tools folder (${e.javaClass.simpleName}). It stays where it was; the app can download a fresh copy."))
        }
    }

    /** Size of what is left of the old folder in bytes, 0 when there is none (for the "Delete old data (X GB)?" prompt). */
    fun leftoverBytes(): Long {
        if (!Files.isDirectory(legacyRoot)) return 0
        return try {
            Files.walk(legacyRoot).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not measure the old data folder", e)
            0
        }
    }

    /** Deletes the old folder. Only call after the user confirmed. */
    fun deleteLeftovers(): Outcome<Unit> {
        if (!Files.exists(legacyRoot)) return Outcome.Success(Unit)
        return try {
            deleteTree(legacyRoot)
            Outcome.Success(Unit)
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not delete the old data folder", e)
            Outcome.Failure(CodedError(ErrorCodes.DATA_DIR_UNAVAILABLE, "Could not delete the old data folder (${e.javaClass.simpleName})."))
        }
    }

    private fun holdsAdb(dir: Path): Boolean = Files.isRegularFile(dir.resolve("adb")) || Files.isRegularFile(dir.resolve("adb.exe"))

    /** Removes [dir] only when it contains no files (the empty skeleton that [DataPaths.ensureDirectories] creates). */
    private fun deleteEmptyTree(dir: Path) {
        val hasFiles = Files.walk(dir).use { s -> s.anyMatch { Files.isRegularFile(it) } }
        if (!hasFiles) deleteTree(dir)
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { s ->
            s.forEach { src ->
                val dest = to.resolve(from.relativize(src).toString())
                if (Files.isDirectory(src)) Files.createDirectories(dest) else Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }

    private fun deleteTree(dir: Path) {
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
}
