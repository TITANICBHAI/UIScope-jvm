package com.titanicbhai.uiscope

import com.titanicbhai.uiscope.storage.DataPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Logger

/**
 * Stages the Windows helper exe (P3-1): the Gradle build puts `helper/UIScope.Helper.exe` on the app classpath on
 * Windows; at start-up it is copied next to the data folder so the helper process can run from a real file. When the
 * resource is absent (non-Windows builds skip the helper build task) or the copy fails, [extract] returns null and the
 * state holder's start-up self-test reports Windows inspection honestly unavailable.
 */
object WindowsHelperExe {
    private val logger = Logger.getLogger("uiscope.windows")
    private const val RESOURCE = "/helper/UIScope.Helper.exe"

    /** The data-folder exe path after staging; null when the helper is not on the classpath or cannot be written. */
    fun extract(paths: DataPaths): Path? {
        val source = WindowsHelperExe::class.java.getResourceAsStream(RESOURCE) ?: return null
        val target = paths.root.resolve("helper").resolve("UIScope.Helper.exe")
        return try {
            Files.createDirectories(target.parent)
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            target
        } catch (t: Throwable) {
            logger.warning("the helper could not be staged next to the data folder: " + t.javaClass.simpleName)
            null
        }
    }
}
