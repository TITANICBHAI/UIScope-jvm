package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.AdbDevice
import com.titanicbhai.uiscope.model.DeviceState
import com.titanicbhai.uiscope.ports.TrackerEvent
import com.titanicbhai.uiscope.testing.FakeAdbServer
import com.titanicbhai.uiscope.testing.blocking
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceTrackerTest {
    private val dispatchers = StandardDispatcherProvider(Dispatchers.Unconfined)
    private fun dev(serial: String) = AdbDevice(serial, DeviceState.DEVICE)

    @Test
    fun `a change on the server reaches the collector within a second`() = blocking {
        FakeAdbServer().use { server ->
            val listing = CopyOnWriteArrayList<AdbDevice>()
            val calls = AtomicInteger()
            val tracker = DeviceTracker(refresh = { calls.incrementAndGet(); Outcome.Success(listing.toList()) }, dispatchers = dispatchers, port = server.port)
            val seen = CopyOnWriteArrayList<List<String>>()
            // the collector runs on its own threads: this test thread blocks while it waits
            val job = tracker.track().onEach { if (it is TrackerEvent.Devices) seen += it.devices.map { d -> d.serial } }.launchIn(CoroutineScope(Dispatchers.Default))
            val initial = System.nanoTime()
            while (seen.isEmpty() && System.nanoTime() - initial < 5_000_000_000L) Thread.sleep(10)
            assertEquals(listOf(emptyList<String>()), seen.toList(), "the initial list arrives without any push")
            val start = System.nanoTime()
            listing += dev("PHONE1")
            server.push("PHONE1\tdevice\n")
            while (seen.none { it == listOf("PHONE1") } && (System.nanoTime() - start) < 5_000_000_000L) Thread.sleep(10)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(seen.any { it == listOf("PHONE1") }, "no update seen: $seen")
            assertTrue(elapsedMs < 1_000, "the update took $elapsedMs ms")
            assertEquals(listOf("host:track-devices"), server.requests.toList())
            job.cancel()
        }
    }

    @Test
    fun `cancelling the collector closes the connection promptly`() = blocking {
        FakeAdbServer().use { server ->
            val tracker = DeviceTracker({ Outcome.Success(emptyList()) }, dispatchers, port = server.port)
            val first = withTimeout(10_000) { tracker.track().first() }
            assertTrue(first is TrackerEvent.Devices || first is TrackerEvent.Problem)
        }
    }

    @Test
    fun `without a server the list is polled, the server is started once and the waits back off`() = blocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val delays = CopyOnWriteArrayList<Long>()
        val started = AtomicInteger()
        val tracker = DeviceTracker({ Outcome.Success(listOf(dev("POLLED"))) }, dispatchers, port = closedPort, startServer = { started.incrementAndGet() }, delayFn = { delays += it })
        val events = withTimeout(30_000) { tracker.track().take(8).toList() }
        assertEquals(8, events.size)
        assertTrue(events.all { it is TrackerEvent.Devices && it.devices.single().serial == "POLLED" })
        assertEquals(1, started.get(), "adb start-server is attempted once")
        // the flow ends after the 8th event, so seven waits have been requested by then
        assertEquals(listOf(3_000L, 3_000L, 3_000L, 6_000L, 12_000L, 24_000L, 30_000L), delays.toList())
    }

    @Test
    fun `a refusal from adb falls back to polling and refresh errors are reported`() = blocking {
        FakeAdbServer(reply = "FAIL").use { server ->
            val tracker = DeviceTracker({ Outcome.Failure(CodedError(ErrorCodes.ADB_START_FAILED, "down")) }, dispatchers, port = server.port, delayFn = {})
            val events = withTimeout(30_000) { tracker.track().take(2).toList() }
            assertTrue(events.all { it is TrackerEvent.Problem && it.error.code == ErrorCodes.ADB_START_FAILED }, events.toString())
        }
    }
}
