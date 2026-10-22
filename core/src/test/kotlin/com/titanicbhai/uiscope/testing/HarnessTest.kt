package com.titanicbhai.uiscope.testing

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The harness itself is tested so a broken fake cannot silently make other tests pass. */
class HarnessTest {
    @Test
    fun `golden files match regardless of line endings and report the first difference`() {
        withTempDir { dir ->
            Files.writeString(dir.resolve("sample.txt"), "line one\r\nline two\r\n")
            GoldenFiles.assertMatches("sample.txt", "line one\nline two\n", dir, update = false)
            val failure = assertFailsWith<AssertionError> { GoldenFiles.assertMatches("sample.txt", "line one\nline TWO\n", dir, update = false) }
            assertTrue(failure.message!!.contains("line 2"), failure.message)
            assertTrue(failure.message!!.contains("line TWO"), failure.message)
        }
    }

    @Test
    fun `a missing golden file fails with instructions instead of passing silently`() {
        withTempDir { dir ->
            val failure = assertFailsWith<AssertionError> { GoldenFiles.assertMatches("absent.txt", "anything", dir, update = false) }
            assertTrue(failure.message!!.contains("UPDATE_GOLDEN=1"), failure.message)
        }
    }

    @Test
    fun `fixtures are loaded byte for byte and a missing fixture fails clearly`() {
        // fixtures/harness/sample.bin holds: 'A', CR, LF, 0xEF, 0xBB, 0xBF
        val bytes = Fixtures.bytes("harness/sample.bin")
        assertEquals(listOf<Byte>(0x41, 0x0D, 0x0A, 0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.toList())
        assertEquals("A\r\n\uFEFF", Fixtures.text("harness/sample.bin"))
        val failure = assertFailsWith<AssertionError> { Fixtures.text("does/not/exist.txt") }
        assertTrue(failure.message!!.contains("does/not/exist.txt"), failure.message)
    }

    @Test
    fun `fake clock only moves when told to`() {
        val clock = FakeClock(Instant.parse("2026-03-04T05:06:07Z"))
        assertEquals(Instant.parse("2026-03-04T05:06:07Z"), clock.instant())
        assertEquals(clock.instant(), clock.instant())
        clock.advance(Duration.ofMinutes(5))
        assertEquals(Instant.parse("2026-03-04T05:11:07Z"), clock.instant())
        clock.set(Instant.parse("2030-01-01T00:00:00Z"))
        assertEquals(2030, clock.instant().atZone(clock.zone).year)
    }

    @Test
    fun `waiting helper fails with its message when the condition never holds`() {
        val failure = assertFailsWith<AssertionError> { awaitTrue(timeoutMs = 100, message = "never true") { false } }
        assertTrue(failure.message!!.contains("never true"), failure.message)
        awaitTrue(timeoutMs = 100) { true }
    }
}
