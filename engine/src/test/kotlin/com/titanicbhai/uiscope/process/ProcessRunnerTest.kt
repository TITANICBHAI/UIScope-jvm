package com.titanicbhai.uiscope.process

import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.testing.FakeProcess
import com.titanicbhai.uiscope.testing.LogCapture
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.blocking
import com.titanicbhai.uiscope.testing.withTempDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Appendix G: ProcessRunnerTest (B10). */
class ProcessRunnerTest {
    private val runner = SystemProcessRunner(StandardDispatcherProvider(Dispatchers.Unconfined))

    @Test
    fun `stdout stderr and a non-zero exit code are reported separately`() = blocking {
        val result = runner.run(FakeProcess.command("stdout-and-stderr", "OUT", "ERR", "3"), 30.seconds).assertSuccess()
        assertEquals(3, result.exitCode)
        assertEquals("OUT", result.stdoutText())
        assertEquals("ERR", result.stderrText())
    }

    @Test
    fun `a missing executable is a coded failure not an exception`() = blocking {
        val error = runner.run(listOf("definitely-not-a-real-binary-uiscope"), 5.seconds).assertFailure(ErrorCodes.PROCESS_START_FAILED)
        assertTrue(error.detail!!.contains("definitely-not-a-real-binary-uiscope"), error.toString())
    }

    @Test
    fun `an empty command is a coded failure`() = blocking {
        runner.run(emptyList(), 5.seconds).assertFailure(ErrorCodes.PROCESS_START_FAILED)
    }

    @Test
    fun `stdin is delivered in full and then closed`() = blocking {
        val result = runner.run(FakeProcess.command("stdin-len"), 30.seconds, stdin = ByteArray(1_000_000) { 7 }).assertSuccess()
        assertEquals("1000000", result.stdoutText().trim())
    }

    @Test
    fun `50 MiB on stdout while stderr also fills does not deadlock`() = blocking {
        val result = runner.run(FakeProcess.command("mixed", "50", "512"), 60.seconds).assertSuccess()
        assertEquals(0, result.exitCode)
        assertEquals(50 * 1024 * 1024, result.stdout.size)
        assertEquals(512 * 1024, result.stderr.size)
    }

    @Test
    fun `timeout kills the whole process tree`() = blocking {
        withTempDir { dir ->
            val pidFile = dir.resolve("pids.txt")
            runner.run(FakeProcess.command("spawn-tree", pidFile.toString(), "60000"), 4.seconds)
                .assertFailure(ErrorCodes.PROCESS_TIMEOUT)
            val pids = FakeProcess.awaitPids(pidFile, 2_000)
            assertEquals(2, pids.size)
            assertTrue(FakeProcess.awaitDead(pids), "parent and child must both be dead after a timeout: $pids")
        }
    }

    @Test
    fun `cancellation kills the whole process tree`() = blocking {
        withTempDir { dir ->
            val pidFile = dir.resolve("pids.txt")
            val job = launch(Dispatchers.Default) {
                runner.run(FakeProcess.command("spawn-tree", pidFile.toString(), "60000"), 60.seconds)
            }
            val pids = FakeProcess.awaitPids(pidFile)
            assertFalse(FakeProcess.awaitDead(pids, 300), "the tree should still be running before the cancel")
            job.cancel()
            withTimeout(15_000) { job.join() }
            assertTrue(FakeProcess.awaitDead(pids), "parent and child must both be dead after a cancel: $pids")
        }
    }

    @Test
    fun `INFO logs carry executable name exit code and duration but never the arguments`() = blocking {
        LogCapture("uiscope").use { capture ->
            runner.run(FakeProcess.command("print", "SECRET-SERIAL-ABC123"), 30.seconds).assertSuccess()
            val log = capture.everything(java.util.logging.Level.INFO)
            assertFalse(log.contains("SECRET-SERIAL-ABC123"), "arguments must not be logged: $log")
            assertTrue(log.contains("exit=0"), log)
            assertTrue(log.contains("durationMs="), log)
        }
    }
}
