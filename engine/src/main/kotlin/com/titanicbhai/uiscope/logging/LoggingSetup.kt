package com.titanicbhai.uiscope.logging

import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Redaction helpers for log lines (plan §3.8). The logs never contain tree contents, element text, window titles or
 * full device serials, and a log call must never be given such values.
 */
object LogSafe {
    /** Device serials are never logged in full: only the last four characters. */
    fun serial(serial: String?): String =
        if (serial.isNullOrEmpty()) "unknown" else "…" + serial.takeLast(SERIAL_TAIL)

    private const val SERIAL_TAIL = 4
}

/** Rotating file log under `<data>/logs`: [DEFAULT_COUNT] files of [DEFAULT_LIMIT_BYTES] each. Close to detach. */
class LoggingSetup private constructor(
    private val logger: Logger,
    private val handler: FileHandler,
    private val previousLevel: Level?,
) : AutoCloseable {

    override fun close() {
        logger.removeHandler(handler)
        handler.close()
        logger.level = previousLevel
    }

    companion object {
        const val LOGGER_NAME = "uiscope"
        const val DEFAULT_LIMIT_BYTES = 1_000_000
        const val DEFAULT_COUNT = 5

        fun install(
            logDir: Path,
            level: Level = Level.INFO,
            limitBytes: Int = DEFAULT_LIMIT_BYTES,
            count: Int = DEFAULT_COUNT,
        ): Outcome<LoggingSetup> =
            try {
                Files.createDirectories(logDir)
                // FileHandler patterns use '/' on every platform.
                val pattern = logDir.resolve("uiscope-%g.log").toString().replace('\\', '/')
                val handler = FileHandler(pattern, limitBytes, count, true)
                handler.formatter = LogFormatter()
                handler.level = Level.ALL
                val logger = Logger.getLogger(LOGGER_NAME)
                val previous = logger.level
                logger.addHandler(handler)
                logger.level = level
                Outcome.Success(LoggingSetup(logger, handler, previous))
            } catch (e: IOException) {
                Outcome.Failure(CodedError(ErrorCodes.DATA_DIR_UNAVAILABLE, "Could not open the log file in $logDir (${e.message})"))
            }
    }
}

/** `2026-10-19T08:00:00.123Z INFO    uiscope.process: message` followed by the stack trace, if any. */
internal class LogFormatter : Formatter() {
    override fun format(record: LogRecord): String {
        val text = StringBuilder()
            .append(record.instant).append(' ')
            .append(record.level.name.padEnd(LEVEL_WIDTH)).append(' ')
            .append(record.loggerName ?: "-").append(": ")
            .append(formatMessage(record)).append('\n')
        record.thrown?.let { thrown ->
            val trace = StringWriter()
            thrown.printStackTrace(PrintWriter(trace))
            text.append(trace)
        }
        return text.toString()
    }

    private companion object {
        const val LEVEL_WIDTH = 7
    }
}
