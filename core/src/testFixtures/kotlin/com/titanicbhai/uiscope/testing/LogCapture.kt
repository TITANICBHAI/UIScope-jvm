package com.titanicbhai.uiscope.testing

import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/** Collects everything logged below [loggerName] so tests can assert on it (for example "no serial in the log"). */
class LogCapture(
    loggerName: String = "uiscope",
    level: Level = Level.ALL,
) : AutoCloseable {
    private val records = CopyOnWriteArrayList<LogRecord>()
    private val logger: Logger = Logger.getLogger(loggerName)
    private val previousLevel: Level? = logger.level
    private val formatter = SimpleFormatter()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records += record
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    init {
        logger.addHandler(handler)
        logger.level = level
    }

    /** Every record at or above [min], formatted as message text (parameters substituted). */
    fun messages(min: Level = Level.ALL): List<String> =
        records.filter { it.level.intValue() >= min.intValue() }.map { formatter.formatMessage(it) }

    /** Messages plus any attached exception text, for "nothing sensitive anywhere" assertions. */
    fun everything(min: Level = Level.ALL): String =
        records.filter { it.level.intValue() >= min.intValue() }.joinToString("\n") { record ->
            formatter.formatMessage(record) + (record.thrown?.let { " " + it } ?: "")
        }

    override fun close() {
        logger.removeHandler(handler)
        logger.level = previousLevel
    }
}
