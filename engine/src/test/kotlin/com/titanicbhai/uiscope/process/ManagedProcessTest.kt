package com.titanicbhai.uiscope.process

import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.testing.FakeProcess
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.awaitTrue
import com.titanicbhai.uiscope.testing.blocking
import com.titanicbhai.uiscope.testing.withTempDir
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagedProcessTest {
    private val launcher = SystemProcessLauncher(StandardDispatcherProvider(Dispatchers.Unconfined))

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `lines arrive in order and the exit code is reported`() = blocking {
        val scope = newScope()
        try {
            val process = launcher.start(FakeProcess.command("emit-lines", "5", "7"), scope).assertSuccess()
            assertEquals((1..5).map { "line-$it" }, withTimeout(30_000) { process.stdoutLines.toList() })
            assertEquals(7, withTimeout(30_000) { process.awaitExit() })
            assertFalse(process.isAlive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stdout and stderr are separate streams`() = blocking {
        val scope = newScope()
        try {
            val process = launcher.start(FakeProcess.command("stdout-and-stderr", "o\n", "e\n", "0"), scope).assertSuccess()
            assertEquals(0, withTimeout(30_000) { process.awaitExit() })
            assertEquals(listOf("o"), withTimeout(30_000) { process.stdoutLines.toList() })
            assertEquals(listOf("e"), withTimeout(30_000) { process.stderrLines.toList() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `writeLine reaches stdin and replies come back in order`() = blocking {
        val scope = newScope()
        try {
            val process = launcher.start(FakeProcess.command("echo-lines"), scope).assertSuccess()
            val received = CopyOnWriteArrayList<String>()
            val collector = process.stdoutLines.onEach { received.add(it) }.launchIn(scope)
            process.writeLine("a").assertSuccess()
            awaitTrue(message = "echo:a") { received.contains("echo:a") }
            process.writeLine("b").assertSuccess()
            awaitTrue(message = "echo:b") { received.contains("echo:b") }
            process.writeLine("quit").assertSuccess()
            assertEquals(0, withTimeout(30_000) { process.awaitExit() })
            withTimeout(30_000) { collector.join() }
            assertEquals(listOf("echo:a", "echo:b"), received.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancelling the owning scope kills the process`() = blocking {
        val scope = newScope()
        val process = launcher.start(FakeProcess.command("sleep", "60000"), scope).assertSuccess()
        assertTrue(process.isAlive)
        scope.cancel()
        assertTrue(FakeProcess.awaitDead(listOf(process.pid)), "process must die when its scope is cancelled")
    }

    @Test
    fun `cancelling the owning scope kills children too`() = blocking {
        withTempDir { dir ->
            val scope = newScope()
            val pidFile = dir.resolve("pids.txt")
            launcher.start(FakeProcess.command("spawn-tree", pidFile.toString(), "60000"), scope).assertSuccess()
            val pids = FakeProcess.awaitPids(pidFile)
            scope.cancel()
            assertTrue(FakeProcess.awaitDead(pids), "parent and child must both die: $pids")
        }
    }

    @Test
    fun `kill ends the process and awaitExit returns`() = blocking {
        val scope = newScope()
        try {
            val process = launcher.start(FakeProcess.command("sleep", "60000"), scope).assertSuccess()
            process.kill()
            process.kill() // idempotent
            withTimeout(15_000) { process.awaitExit() }
            assertFalse(process.isAlive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a missing executable is a coded failure`() = blocking {
        val scope = newScope()
        try {
            launcher.start(listOf("definitely-not-a-real-binary-uiscope"), scope).assertFailure(ErrorCodes.PROCESS_START_FAILED)
        } finally {
            scope.cancel()
        }
    }
}
