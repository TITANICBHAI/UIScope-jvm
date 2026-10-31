package com.titanicbhai.uiscope.windows

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.process.SystemProcessLauncher
import com.titanicbhai.uiscope.testing.FakeProcess
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.awaitTrue
import com.titanicbhai.uiscope.testing.blocking
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** G-HELPER (plan P3-2): handshake, version refusal, timeout restarts with the 3/minute cap, malformed lines ignored, event order. */
class WindowsHelperClientTest {

    private val dispatchers: DispatcherProvider = StandardDispatcherProvider(kotlinx.coroutines.Dispatchers.Default)
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private fun client(vararg flags: String, timeout: kotlin.time.Duration = 5.seconds) = WindowsHelperClient(
        launcher = SystemProcessLauncher(dispatchers),
        helperCommand = FakeProcess.command("helper", *flags),
        dispatchers = dispatchers,
        requestTimeout = timeout,
        logger = Logger.getLogger("test.helper"),
    )

    @Test
    fun `handshake succeeds and requests correlate`() = blocking {
        val helper = client()
        helper.start(scope).assertSuccess()
        val response = helper.request("ping").assertSuccess()
        assertEquals("ping", response["type"])
        helper.stop()
        scope.cancelChildren()
    }

    @Test
    fun `a different major protocol version is refused with a clear error`() = blocking {
        val helper = client("bad-version")
        val result = helper.start(scope)
        assertTrue(result is Outcome.Failure, "the handshake must fail")
        assertTrue(result.error.detail.orEmpty().contains("protocol"), "the error names the version mismatch: ${result.error.detail}")
        assertEquals(ErrorCodes.UIA_UNAVAILABLE, result.error.code)
        scope.cancelChildren()
    }

    @Test
    fun `a timed-out request restarts the helper and the next request works`() = blocking {
        val helper = client(timeout = 300.milliseconds)
        helper.start(scope).assertSuccess()
        helper.request("never", timeout = 300.milliseconds)
        // The helper was restarted after the timeout; a normal request works again.
        val response = helper.request("ping").assertSuccess()
        assertEquals("ping", response["type"])
        helper.stop()
        scope.cancelChildren()
    }

    @Test
    fun `more than three restarts a minute give up with UIA_UNAVAILABLE`() = blocking {
        val helper = client("slow", timeout = 200.milliseconds)
        helper.start(scope).assertSuccess()
        repeat(3) { helper.request("ping", timeout = 200.milliseconds) } // each times out and restarts
        val fourth = helper.request("ping", timeout = 200.milliseconds)
        assertTrue(fourth is Outcome.Failure, "the restart cap gives up")
        assertEquals(ErrorCodes.UIA_UNAVAILABLE, fourth.error.code)
        helper.stop()
        scope.cancelChildren()
    }

    @Test
    fun `malformed lines are ignored and requests still work`() = blocking {
        val helper = client("malformed")
        helper.start(scope).assertSuccess()
        repeat(3) { helper.request("ping").assertSuccess() }
        helper.stop()
        scope.cancelChildren()
    }

    @Test
    fun `events arrive in order before the response`() = blocking {
        val helper = client()
        helper.start(scope).assertSuccess()
        val received = CompletableDeferred<List<String>>()
        val collector = scope.launch {
            received.complete(helper.events.take(3).toList().map { it.name })
        }
        helper.request("events").assertSuccess()
        val names = withTimeout(10.seconds) { received.await() }
        assertEquals(listOf("event-a", "event-b", "event-c"), names)
        helper.stop()
        scope.cancelChildren()
    }

    @Test
    fun `a helper that dies is restarted on the next request`() = blocking {
        val helper = client()
        helper.start(scope).assertSuccess()
        helper.request("crash", timeout = 500.milliseconds) // helper exits; the response never arrives
        awaitTrue(10_000, "the dead helper is replaced") { helper.request("ping", timeout = 2.seconds).isOk() }
        helper.stop()
        scope.cancelChildren()
    }

    private fun Outcome<Map<String, Any?>>.isOk() = this is Outcome.Success
}
