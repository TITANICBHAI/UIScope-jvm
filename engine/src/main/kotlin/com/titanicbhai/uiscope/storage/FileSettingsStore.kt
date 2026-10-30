package com.titanicbhai.uiscope.storage

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.settings.InMemorySettingsStore
import com.titanicbhai.uiscope.settings.Settings
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings in `<data>/settings.json`. Reads happen once, at construction; every [update] is visible to the next read immediately and
 * is written to disk afterwards (debounced, atomically via a temporary file). A file that cannot be read is kept as
 * `settings.json.corrupt` and the defaults are used, so a bad file never blocks start-up and is never silently destroyed.
 * Call [flush] when the application exits.
 */
class FileSettingsStore(
    private val file: Path,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val debounceMs: Long = 300,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val logger: Logger = Logger.getLogger("uiscope.settings"),
) : InMemorySettingsStore(load(file, logger)) {
    @Volatile private var pending: Job? = null

    override fun changed() {
        pending?.cancel()
        pending = scope.launch(dispatchers.io) {
            delayFn(debounceMs)
            write(settings.value)
        }
    }

    /** Writes the current settings now (and cancels a waiting debounced write). */
    fun flush() {
        pending?.cancel()
        write(settings.value)
    }

    @Synchronized
    private fun write(value: Settings) {
        try {
            Files.createDirectories(file.toAbsolutePath().parent)
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(temp, SettingsJson.encode(value))
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                logger.log(Level.FINE, "atomic move not supported, using a plain move", e)
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not save settings", e)
        }
    }

    companion object {
        fun load(file: Path, logger: Logger = Logger.getLogger("uiscope.settings")): Settings {
            if (!Files.isRegularFile(file)) return Settings()
            val text = try {
                Files.readString(file)
            } catch (e: IOException) {
                logger.log(Level.WARNING, "could not read settings, using defaults", e)
                return Settings()
            }
            return when (val decoded = SettingsJson.decode(text)) {
                is Outcome.Success -> decoded.value
                is Outcome.Failure -> {
                    logger.warning("settings.json is damaged (${decoded.error.detail}); using defaults and keeping a copy")
                    keepCopy(file, logger)
                    Settings()
                }
            }
        }

        private fun keepCopy(file: Path, logger: Logger) {
            try {
                Files.copy(file, file.resolveSibling(file.fileName.toString() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                logger.log(Level.WARNING, "could not keep a copy of the damaged settings file", e)
            }
        }
    }
}
