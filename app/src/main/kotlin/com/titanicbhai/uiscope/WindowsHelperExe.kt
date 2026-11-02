package com.titanicbhai.uiscope

import com.titanicbhai.uiscope.storage.DataPaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Stages the Windows helper exe (P3-1): the Gradle build puts `helper/UIScope.Helper.exe` on the app classpath on
 * Windows; at start-up it is copied next to the data folder so the helper process can run from a real file. When the
 * resource is absent (non-Windows builds skip the helper build task) or nothing usable can be written, [extract] returns null and the
 * state holder's start-up self-test reports Windows inspection honestly unavailable.
 *
 * Copy rules, learned the hard way: the resource stream is always closed; the copy goes to a temporary name and is moved into place, so a
 * crash mid-copy never leaves a truncated exe that would "start" and fail; an exe that is already in place and identical is left alone
 * (an older helper that is still running keeps the file locked, and replacing it would fail); and when the copy fails but an earlier exe
 * is there, that one is used instead of giving up.
 */
object WindowsHelperExe {
    private val logger = Logger.getLogger("uiscope.windows")
    private const val RESOURCE = "/helper/UIScope.Helper.exe"

    /** The data-folder exe path after staging; null when the helper is not on the classpath or cannot be written. */
    fun extract(paths: DataPaths): Path? {
        val target = paths.root.resolve("helper").resolve("UIScope.Helper.exe")
        val stream = WindowsHelperExe::class.java.getResourceAsStream(RESOURCE)
        if (stream == null) {
            // Never silent: a missing resource means every Windows inspection attempt will fail downstream.
            logger.warning("the helper resource $RESOURCE is not on the classpath; Windows inspection will be unavailable")
            return existing(target)
        }
        return stream.use { source ->
            try {
                Files.createDirectories(target.parent)
                val bytes = source.readAllBytes()
                if (Files.isRegularFile(target) && Files.size(target) == bytes.size.toLong() && Files.readAllBytes(target).contentEquals(bytes)) {
                    return@use target
                }
                val partial = target.resolveSibling("UIScope.Helper.exe.part")
                Files.write(partial, bytes)
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
                target
            } catch (e: IOException) {
                // Typically "being used by another process": a helper from an earlier run is still alive. Use it rather than nothing.
                logger.log(Level.WARNING, "the helper could not be staged next to the data folder (${e.javaClass.simpleName}: ${e.message})", e)
                existing(target)
            }
        }
    }

    private fun existing(target: Path): Path? = target.takeIf { Files.isRegularFile(it) }
}
