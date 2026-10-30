package com.titanicbhai.uiscope.state

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.error.UnexpectedError
import com.titanicbhai.uiscope.model.AdbDevice
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.DeviceInfo
import com.titanicbhai.uiscope.model.DeviceState
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.ports.AdbPort
import com.titanicbhai.uiscope.ports.AdbStatus
import com.titanicbhai.uiscope.ports.TrackerEvent
import com.titanicbhai.uiscope.settings.InMemorySettingsStore
import com.titanicbhai.uiscope.settings.Settings
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.n
import com.titanicbhai.uiscope.testing.awaitTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** One test per transition of §4.3. The "main" dispatcher is a thread named ui-main; no port call may ever run on it. */
class AndroidInspectStateTest {
    private fun named(name: String): CoroutineDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }.asCoroutineDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val main = named("ui-main")
        override val io = Executors.newCachedThreadPool { r -> Thread(r, "ui-io").apply { isDaemon = true } }.asCoroutineDispatcher()
        override val default = named("ui-default")
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)

    private class FakePort : AdbPort {
        var locateResult: Outcome<AdbStatus> = Outcome.Success(AdbStatus("adb", "1.0.41", "PATH"))
        val feed = MutableSharedFlow<TrackerEvent>(replay = 1)
        val threads = CopyOnWriteArrayList<String>()
        val captureCalls = AtomicInteger()
        val captured = CopyOnWriteArrayList<AdbDevice>()
        var handler: suspend (AdbDevice) -> Outcome<Capture> = { Outcome.Success(screen(it)) }

        override suspend fun locate(): Outcome<AdbStatus> {
            threads += Thread.currentThread().name
            return locateResult
        }

        override fun trackDevices(): Flow<TrackerEvent> = flow {
            threads += Thread.currentThread().name
            emitAll(feed)
        }

        override suspend fun capture(device: AdbDevice): Outcome<Capture> {
            threads += Thread.currentThread().name
            captureCalls.incrementAndGet()
            captured += device
            return handler(device)
        }

        fun devices(vararg d: AdbDevice) {
            feed.tryEmit(TrackerEvent.Devices(d.toList()))
        }
    }

    private val port = FakePort()
    private val settings = InMemorySettingsStore()
    private val delays = CopyOnWriteArrayList<Long>()
    private val ticks = Channel<Unit>(Channel.UNLIMITED)

    private fun state(instantLive: Boolean = false) = AndroidInspectState(
        port, settings, dispatchers, scope,
        delayFn = { ms -> delays += ms; if (!instantLive) ticks.receive() },
    )

    private val phone = AdbDevice("PHONE1", DeviceState.DEVICE, "Pixel 7")
    private val tablet = AdbDevice("TABLET1", DeviceState.DEVICE, "Tab")

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun waitFor(message: String, predicate: () -> Boolean) = awaitTrue(10_000, message, predicate)

    private fun AndroidInspectState.awaitState(message: String, predicate: (AndroidState) -> Boolean) = waitFor(message) { predicate(ui.value.state) }

    private fun collectEvents(s: AndroidInspectState): CopyOnWriteArrayList<AndroidEvent> {
        val list = CopyOnWriteArrayList<AndroidEvent>()
        s.events.onEach { list += it }.launchIn(CoroutineScope(SupervisorJob() + dispatchers.io))
        Thread.sleep(50) // let the collector subscribe before anything is emitted
        return list
    }

    companion object {
        fun screen(device: AdbDevice, title: String = "Hello", shift: Int = 0, extra: Boolean = false): Capture = TestTrees.capture(
            listOf(
                n(
                    "android.widget.FrameLayout", bounds = Bounds(0, 0, 1080, 2400),
                    children = listOfNotNull(
                        n("android.widget.TextView", identifier = "app:id/title", text = title, bounds = Bounds(shift, 10, 300, 80)),
                        if (extra) n("android.widget.Button", identifier = "app:id/ok", text = "OK") else null,
                        n("android.widget.ListView", identifier = "app:id/list", bounds = Bounds(0, 100, 1080, 2000), children = listOf(n("android.widget.TextView", identifier = "app:id/row", text = "row"))),
                    ),
                ),
            ),
            id = "cap-${System.nanoTime()}", target = Target.Android(DeviceInfo(device.serial, device.model), "app"),
        )
    }

    // ── adb and device list ─────────────────────────────────────────────────────────────────────

    @Test
    fun `adb missing, then found after the user fixes it`() {
        port.locateResult = Outcome.Failure(CodedError(ErrorCodes.ADB_NOT_FOUND, "none"))
        val s = state()
        s.start()
        s.awaitState("AdbMissing") { it is AndroidState.AdbMissing }
        assertEquals(ErrorCodes.ADB_NOT_FOUND, s.ui.value.problem?.code)
        port.locateResult = Outcome.Success(AdbStatus("C:\\tools\\adb.exe", "1.0.41 (35.0.0)", "SAVED"))
        s.start()
        s.awaitState("NoDevice") { it is AndroidState.NoDevice }
        assertEquals("C:\\tools\\adb.exe", s.ui.value.adb?.path)
        assertNull(s.ui.value.problem)
    }

    @Test
    fun `no device, then one usable device is selected and captured once by itself`() {
        val s = state()
        s.start()
        port.devices()
        s.awaitState("NoDevice") { it is AndroidState.NoDevice }
        port.devices(phone)
        s.awaitState("Captured") { it is AndroidState.Captured }
        assertEquals(1, port.captureCalls.get())
        assertEquals("PHONE1", s.ui.value.selectedSerial)
        assertEquals("PHONE1", settings.settings.value.lastSerial, "the last serial is remembered")
        val ui = s.ui.value
        assertSame(ui.capture, ui.index!!.capture, "tree and index belong together")
        assertEquals(ui.capture, (ui.state as AndroidState.Captured).capture)
    }

    @Test
    fun `unauthorized, offline and no permissions are shown, and a device that becomes usable is captured`() {
        val s = state()
        s.start()
        val unauthorized = AdbDevice("PHONE1", DeviceState.UNAUTHORIZED)
        port.devices(unauthorized)
        s.awaitState("Unauthorized") { it is AndroidState.Unauthorized }
        port.devices(AdbDevice("PHONE1", DeviceState.OFFLINE))
        s.awaitState("Offline") { it is AndroidState.Offline }
        port.devices(AdbDevice("PHONE1", DeviceState.NO_PERMISSIONS))
        s.awaitState("NoPermissions") { it is AndroidState.NoPermissions }
        port.devices(AdbDevice("PHONE1", DeviceState.CONNECTING))
        s.awaitState("Offline again") { it is AndroidState.Offline }
        assertEquals(0, port.captureCalls.get(), "nothing is captured from a device that cannot be used")
        port.devices(phone)
        s.awaitState("Captured") { it is AndroidState.Captured }
        assertEquals(1, port.captureCalls.get())
    }

    @Test
    fun `several usable devices ask which one, and the remembered one is chosen by itself`() {
        val s = state()
        s.start()
        port.devices(phone, tablet)
        s.awaitState("ChooseDevice") { it is AndroidState.ChooseDevice }
        assertEquals(0, port.captureCalls.get())
        s.selectDevice("TABLET1")
        s.awaitState("Captured") { it is AndroidState.Captured }
        assertEquals("TABLET1", settings.settings.value.lastSerial)
        assertEquals("Tab", (s.ui.value.state as AndroidState.Captured).device.model)

        val second = AndroidInspectState(FakePort().also { it.devices(phone, tablet) }, settings, dispatchers, CoroutineScope(SupervisorJob() + dispatchers.main), delayFn = {})
        second.start()
        second.awaitState("remembered device captured") { it is AndroidState.Captured }
        assertEquals("TABLET1", second.ui.value.selectedSerial)
    }

    // ── capture ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a failed capture keeps the last good capture`() {
        val s = state()
        s.start()
        port.devices(phone)
        s.awaitState("Captured") { it is AndroidState.Captured }
        val good = s.ui.value.capture
        port.handler = { Outcome.Failure(CodedError(ErrorCodes.DUMP_TIMEOUT, "slow")) }
        assertTrue(s.capture())
        s.awaitState("CaptureFailed") { it is AndroidState.CaptureFailed }
        val failed = s.ui.value.state as AndroidState.CaptureFailed
        assertEquals(ErrorCodes.DUMP_TIMEOUT, failed.error.code)
        assertSame(good, failed.lastCapture)
        assertSame(good, s.ui.value.capture)
        port.handler = { Outcome.Success(screen(it)) }
        assertTrue(s.capture())
        s.awaitState("Captured again") { it is AndroidState.Captured }
    }

    @Test
    fun `a second capture request while one runs is ignored with a cue`() {
        val gate = CompletableDeferred<Unit>()
        port.handler = { gate.await(); Outcome.Success(screen(it)) }
        val s = state()
        val events = collectEvents(s)
        s.start()
        port.devices(phone)
        s.awaitState("Capturing") { it is AndroidState.Capturing }
        assertFalse(s.capture())
        waitFor("cue") { events.contains(AndroidEvent.AlreadyCapturing) }
        assertEquals(1, port.captureCalls.get())
        gate.complete(Unit)
        s.awaitState("Captured") { it is AndroidState.Captured }
        assertEquals(1, port.captureCalls.get())
        assertTrue(s.capture(), "a new request is accepted once the first has finished")
    }

    @Test
    fun `cancelling a capture stops the work and keeps the previous capture`() {
        val s = state()
        s.start()
        port.devices(phone)
        s.awaitState("Captured") { it is AndroidState.Captured }
        val previous = s.ui.value.capture
        val cancelled = CompletableDeferred<Unit>()
        val running = CompletableDeferred<Unit>()
        port.handler = {
            running.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        assertTrue(s.capture())
        s.awaitState("Capturing") { it is AndroidState.Capturing }
        runBlocking { kotlinx.coroutines.withTimeout(10_000) { running.await() } } // cancel only once the port call is really running
        s.cancelCapture()
        runBlocking { kotlinx.coroutines.withTimeout(10_000) { cancelled.await() } }
        s.awaitState("back to Captured") { it is AndroidState.Captured }
        assertSame(previous, s.ui.value.capture)
        port.handler = { Outcome.Success(screen(it)) }
        assertTrue(s.capture(), "capturing works again after a cancel")
    }

    @Test
    fun `a result that arrives after a cancel or an unplug is thrown away`() {
        val gate = CompletableDeferred<Unit>()
        val running = CompletableDeferred<Unit>()
        // a port that does not honour cancellation: its answer still arrives later
        port.handler = { running.complete(Unit); kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }; Outcome.Success(screen(it, title = "STALE")) }
        val s = state()
        s.start()
        port.devices(phone)
        s.awaitState("Capturing") { it is AndroidState.Capturing }
        runBlocking { kotlinx.coroutines.withTimeout(10_000) { running.await() } }
        s.cancelCapture()
        s.awaitState("back to Ready") { it is AndroidState.Ready }
        gate.complete(Unit)
        Thread.sleep(300)
        assertNull(s.ui.value.capture, "the cancelled capture's answer must not appear")
        assertTrue(s.ui.value.state is AndroidState.Ready, s.ui.value.state.toString())
        // and a new capture works normally afterwards
        port.handler = { Outcome.Success(screen(it, title = "FRESH")) }
        assertTrue(s.capture())
        s.awaitState("Captured") { it is AndroidState.Captured }
        assertEquals("FRESH", s.ui.value.capture!!.roots.single().children.first().text)
    }

    @Test
    fun `a port that throws becomes an unexpected error, not a crash`() {
        port.handler = { throw IllegalStateException("boom") }
        val s = state()
        s.start()
        port.devices(phone)
        s.awaitState("CaptureFailed") { it is AndroidState.CaptureFailed }
        assertIs<UnexpectedError>((s.ui.value.state as AndroidState.CaptureFailed).error)
    }

    // ── disconnect and reconnect ───────────────────────────────────────────────────────────────

    @Test
    fun `unplugging keeps the last capture, pauses Live, and the same serial coming back says reconnected`() {
        val s = state()
        val events = collectEvents(s)
        s.start()
        port.devices(phone)
        s.awaitState("Captured") { it is AndroidState.Captured }
        s.setLive(true)
        waitFor("a live tick is waiting") { delays.isNotEmpty() } // Live captured right away and is now waiting for its interval
        val last = s.ui.value.capture
        port.devices()
        s.awaitState("Disconnected") { it is AndroidState.Disconnected }
        val gone = s.ui.value.state as AndroidState.Disconnected
        assertEquals("PHONE1", gone.serial)
        assertSame(last, gone.lastCapture)
        assertSame(last, s.ui.value.capture, "the last capture stays on screen")
        assertEquals(LivePause.DeviceDisconnected, s.ui.value.live.pause)
        val callsBefore = port.captureCalls.get()
        ticks.trySend(Unit)
        Thread.sleep(150)
        assertEquals(callsBefore, port.captureCalls.get(), "Live does not capture while disconnected")
        port.devices(phone)
        s.awaitState("Captured again") { it is AndroidState.Captured }
        waitFor("reconnected cue") { events.contains(AndroidEvent.Reconnected) }
        assertSame(last, s.ui.value.capture, "reconnecting does not replace the capture by itself")
        assertEquals(LivePause.DeviceDisconnected, s.ui.value.live.pause, "Live stays paused until the user resumes it")
    }

    @Test
    fun `unplugging during a capture cancels it`() {
        val started = CompletableDeferred<Unit>()
        port.handler = { started.complete(Unit); awaitCancellation() }
        val s = state()
        s.start()
        port.devices(phone)
        s.awaitState("Capturing") { it is AndroidState.Capturing }
        runBlocking { kotlinx.coroutines.withTimeout(10_000) { started.await() } }
        port.devices()
        s.awaitState("Disconnected") { it is AndroidState.Disconnected }
        Thread.sleep(150)
        assertTrue(s.ui.value.state is AndroidState.Disconnected, "a cancelled capture must not overwrite the state")
        assertNull(s.ui.value.capture)
    }

    // ── live ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Live captures again after each interval and reads the interval from settings every time`() {
        val s = state()
        s.start()
        port.devices(phone)
        s.awaitState("Captured") { it is AndroidState.Captured }
        assertEquals(1, port.captureCalls.get())
        s.setLive(true)
        waitFor("first wait") { delays.size == 1 }
        assertEquals(listOf(2_000L), delays.toList())
        assertEquals(2, port.captureCalls.get(), "Live captured right away, then waits")
        settings.update { it.copy(liveIntervalSeconds = 5) }
        ticks.trySend(Unit)
        waitFor("second wait") { delays.size == 2 }
        assertEquals(listOf(2_000L, 5_000L), delays.toList(), "a changed interval applies to the next wait")
        assertEquals(3, port.captureCalls.get())
        s.setLive(false)
        assertEquals(LiveState(), s.ui.value.live)
        ticks.trySend(Unit)
        Thread.sleep(150)
        assertEquals(3, port.captureCalls.get(), "nothing captures once Live is off")
    }

    @Test
    fun `Live waits while the window is hidden and continues when it is shown`() {
        val s = state()
        s.start()
        port.devices(phone)
        s.awaitState("Captured") { it is AndroidState.Captured }
        s.setLive(true)
        waitFor("first wait") { delays.size == 1 }
        s.setVisibility(minimized = true, inspectOpen = true)
        ticks.trySend(Unit)
        Thread.sleep(200)
        assertEquals(2, port.captureCalls.get(), "no capture while minimized")
        s.setVisibility(minimized = false, inspectOpen = false)
        Thread.sleep(100)
        assertEquals(2, port.captureCalls.get(), "no capture while another destination is open")
        s.setVisibility(minimized = false, inspectOpen = true)
        waitFor("capture after showing") { port.captureCalls.get() == 3 }
    }

    @Test
    fun `Live stops after three failures in a row and Resume continues`() {
        val s = state(instantLive = true)
        s.start()
        port.devices(phone)
        s.awaitState("Captured") { it is AndroidState.Captured }
        port.handler = { Outcome.Failure(CodedError(ErrorCodes.DUMP_TIMEOUT, "slow")) }
        s.setLive(true)
        waitFor("paused") { s.ui.value.live.pause is LivePause.Failures }
        assertEquals(ErrorCodes.DUMP_TIMEOUT, (s.ui.value.live.pause as LivePause.Failures).error.code)
        assertTrue(s.ui.value.live.enabled)
        val calls = port.captureCalls.get()
        assertEquals(1 + AndroidInspectState.MAX_LIVE_FAILURES, calls, "exactly three Live attempts after the first capture")
        Thread.sleep(150)
        assertEquals(calls, port.captureCalls.get(), "no more attempts while paused")
        port.handler = { Outcome.Success(screen(it)) }
        s.resumeLive()
        waitFor("captures again") { port.captureCalls.get() > calls && s.ui.value.live.pause == null }
        s.setLive(false)
    }

    @Test
    fun `a success between failures resets the failure count`() {
        val s = state(instantLive = true)
        s.start()
        port.devices(phone)
        s.awaitState("Captured") { it is AndroidState.Captured }
        val sequence = AtomicInteger()
        port.handler = { if (sequence.incrementAndGet() % 3 == 0) Outcome.Success(screen(it)) else Outcome.Failure(CodedError(ErrorCodes.DUMP_TIMEOUT, "slow")) }
        s.setLive(true)
        waitFor("many attempts") { port.captureCalls.get() > 12 }
        assertNull(s.ui.value.live.pause, "failures never reached three in a row")
        s.setLive(false)
    }

    // ── selection restored by identity ──────────────────────────────────────────────────────────

    @Test
    fun `selection and expansion survive a new capture, and a vanished element is reported`() {
        val s = state()
        val events = collectEvents(s)
        s.start()
        port.devices(phone)
        s.awaitState("Captured") { it is AndroidState.Captured }
        val title = ElementPath("0/0")
        val list = ElementPath("0/1")
        s.select(title)
        s.toggleExpanded(list)
        assertEquals(title, s.ui.value.selected)
        // same screen, everything moved: the same elements are selected and expanded
        port.handler = { Outcome.Success(screen(it, shift = 40)) }
        s.capture()
        waitFor("second capture") { s.ui.value.capture?.roots?.single()?.children?.first()?.bounds?.x == 40 }
        assertEquals(title, s.ui.value.selected)
        assertEquals(setOf(list), s.ui.value.expanded)
        // a banner appears first: every path shifts by one, identity still finds the title and the list
        port.handler = { Outcome.Success(screen(it, extra = true)) }
        s.capture()
        waitFor("third capture") { s.ui.value.capture?.roots?.single()?.children?.size == 3 }
        assertEquals(ElementPath("0/0"), s.ui.value.selected)
        assertEquals(setOf(ElementPath("0/2")), s.ui.value.expanded, "the list moved from 0/1 to 0/2")
        // the title disappears
        port.handler = { Outcome.Success(TestTrees.capture(listOf(n("android.widget.FrameLayout", children = listOf(n("android.widget.ListView", identifier = "app:id/list")))), target = Target.Android(DeviceInfo(it.serial), "app"))) }
        s.capture()
        waitFor("selection lost") { s.ui.value.selected == null }
        waitFor("cue") { events.contains(AndroidEvent.SelectionLost) }
    }

    @Test
    fun `only elements of the current capture can be selected`() {
        val s = state()
        s.start()
        port.devices(phone)
        s.awaitState("Captured") { it is AndroidState.Captured }
        s.select(ElementPath("9/9"))
        assertNull(s.ui.value.selected)
        s.select(ElementPath("0/0"))
        assertEquals(ElementPath("0/0"), s.ui.value.selected)
        s.select(null)
        assertNull(s.ui.value.selected)
    }

    // ── threads ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no adb work ever runs on the main thread`() {
        val s = state(instantLive = true)
        s.start()
        port.devices(phone)
        s.awaitState("Captured") { it is AndroidState.Captured }
        s.capture()
        s.setLive(true)
        waitFor("some live captures") { port.captureCalls.get() >= 4 }
        s.setLive(false)
        s.stop()
        assertTrue(port.threads.size >= 6, "only ${port.threads.size} port calls were recorded")
        // Release kotlinx-coroutines artifacts run with debug mode on: a thread's name gains " @coroutine#N"
        // while a coroutine occupies it, so the guards must match the pool prefix, not the exact name.
        assertTrue(port.threads.none { it.startsWith("ui-main") }, "port calls on the main thread: ${port.threads.filter { it.startsWith("ui-main") }.size}")
        assertTrue(port.threads.all { it.startsWith("ui-io") }, port.threads.toSet().toString())
    }

    @Test
    fun `settings default to a 2 second Live interval`() {
        assertEquals(2, Settings().liveIntervalSeconds)
    }
}
