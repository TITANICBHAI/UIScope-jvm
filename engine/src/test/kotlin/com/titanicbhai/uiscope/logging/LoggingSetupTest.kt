package com.titanicbhai.uiscope.logging

import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
import java.util.stream.Collectors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggingSetupTest {
    private fun logFiles(dir: Path): List<String> =
        Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.collect(Collectors.toList()) }
            .filter { it.endsWith(".log") }
            .sorted()

    @Test
    fun `defaults are 1 MB times 5 files`() {
        assertEquals(1_000_000, LoggingSetup.DEFAULT_LIMIT_BYTES)
        assertEquals(5, LoggingSetup.DEFAULT_COUNT)
    }

    @Test
    fun `logs are written under the log folder and rotate`() {
        withTempDir { dir ->
            val logDir = dir.resolve("logs")
            val setup = LoggingSetup.install(logDir, Level.INFO, limitBytes = 20_000, count = 3).assertSuccess()
            try {
                val log = Logger.getLogger("uiscope.test.rotation")
                repeat(400) { log.info("x".repeat(2_000) + " #$it") }
            } finally {
                setup.close()
            }
            val files = logFiles(logDir)
            assertTrue(files.size in 2..3, "expected 2-3 rotated files, found $files")
            files.forEach { name ->
                assertTrue(Files.size(logDir.resolve(name)) <= 20_000 + 4_000, "$name grew past the limit")
            }
        }
    }

    @Test
    fun `lines carry time level logger and message and exceptions keep their stack trace`() {
        withTempDir { dir ->
            val setup = LoggingSetup.install(dir, Level.INFO).assertSuccess()
            try {
                val log = Logger.getLogger("uiscope.test.format")
                log.info("hello log")
                log.log(Level.WARNING, "it failed", IllegalStateException("kaboom"))
                log.fine("below the level, must not appear")
            } finally {
                setup.close()
            }
            val text = Files.readString(dir.resolve("uiscope-0.log"))
            assertTrue(text.contains("INFO") && text.contains("uiscope.test.format") && text.contains("hello log"), text)
            assertTrue(text.contains("WARNING") && text.contains("it failed"), text)
            assertTrue(text.contains("IllegalStateException") && text.contains("kaboom"), text)
            assertFalse(text.contains("below the level"), text)
        }
    }

    @Test
    fun `closing the setup stops logging to the file`() {
        withTempDir { dir ->
            val setup = LoggingSetup.install(dir, Level.INFO).assertSuccess()
            setup.close()
            Logger.getLogger("uiscope.test.after").info("after close")
            val text = Files.readString(dir.resolve("uiscope-0.log"))
            assertFalse(text.contains("after close"), text)
        }
    }

    @Test
    fun `serials are reduced to their last four characters`() {
        assertEquals("…3456", LogSafe.serial("ABCDEF123456"))
        assertEquals("…3456", LogSafe.serial("3456"))
        assertEquals("…ab", LogSafe.serial("ab"))
        assertEquals("unknown", LogSafe.serial(null))
        assertEquals("unknown", LogSafe.serial(""))
        assertFalse(LogSafe.serial("emulator-5554-SECRETSERIAL").contains("SECRET"))
    }
}
