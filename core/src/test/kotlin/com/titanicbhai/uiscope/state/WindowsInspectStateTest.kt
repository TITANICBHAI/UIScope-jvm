package com.titanicbhai.uiscope.state

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.ports.PickEvent
import com.titanicbhai.uiscope.ports.PickedElement
import com.titanicbhai.uiscope.ports.WindowInfo as WindowInfoType
import com.titanicbhai.uiscope.ports.WindowsInspectorPort
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.n
import com.titanicbhai.uiscope.testing.awaitTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** P3-8: one test per transition of 4.4 (Windows), on a fake port. The "main" dispatcher is a thread named ui-main; no port call may ever run on it. */
class WindowsInspectStateTest {
    private fun named(name: String): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }.asCoroutineDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val main = named("ui-main")
        override val io = Executors.newCachedThreadPool { r -> Thread(r, "ui-io").apply { isDaemon = true } }.asCoroutineDispatcher()
        override val default = named("ui-default")
    }
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatchers.main)

    private val notepad = WindowInfoType("0x00120A3C", "Untitled - Notepad", "notepad.exe", 1234, false)
    private val elevated = WindowInfoType("0x0000BEEF", "Task Manager", "Taskmgr.exe", 99, true)

    private class FakePort : WindowsInspectorPort {
        var listWindowsResult: Outcome<List<WindowInfoType>> =
            Outcome.Success(listOf(notepadStatic, elevatedStatic))
        val captureResults = ArrayDeque<Outcome<Capture>>()
        val threads = CopyOnWriteArrayList<String>()
        val startedPicks = AtomicInteger()
        val stoppedPicks = AtomicInteger()
        val pickFeed = MutableSharedFlow<PickEvent>(replay = 1)

        override suspend fun listWindows(): Outcome<List<WindowInfoType>> {
            threads += Thread.currentThread().name
            return listWindowsResult
        }

        override suspend fun captureWindow(hwnd: String): Outcome<Capture> {
            threads += Thread.currentThread().name
            return captureResults.removeFirstOrNull() ?: Outcome.Success(screenFor(hwnd))
        }

        override suspend fun startPick(hideFromCapture: Boolean): Outcome<Unit> {
            threads += Thread.currentThread().name
            startedPicks.incrementAndGet()
            return Outcome.Success(Unit)
        }

        override suspend fun stopPick(): Outcome<Unit> {
            stoppedPicks.incrementAndGet()
            return Outcome.Success(Unit)
        }

        override fun pickEvents(): Flow<PickEvent> = pickFeed

        override suspend fun registerHotkey(modifiers: String, key: String): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun unregisterHotkey(): Outcome<Unit> = Outcome.Success(Unit)

        companion object {
            val notepadStatic = WindowInfoType("0x00120A3C", "Untitled - Notepad", "notepad.exe", 1234, false)
            val elevatedStatic = WindowInfoType("0x0000BEEF", "Task Manager", "Taskmgr.exe", 99, true)

            fun screenFor(hwnd: String): Capture = TestTrees.capture(
                n(identifier = "app:id/root", children = listOf(
                    n(className = "Button", identifier = "app:id/ok", text = "OK", bounds = Bounds(400, 400, 200, 200)),
                )),
                id = "win-$hwnd",
            )
        }
    }

    private val port = FakePort()

    private fun state() = WindowsInspectState(port, dispatchers, scope)

    private fun waitFor(message: String, predicate: () -> Boolean) = awaitTrue(10_000, message, predicate)

    private fun WindowsInspectState.awaitState(message: String, predicate: (WindowsState) -> Boolean) =
        waitFor(message) { predicate(ui.value.state) }

    @Test
    fun `a failing helper self-test lands in HelperMissing`() = kotlinx.coroutines.runBlocking {
        port.listWindowsResult = Outcome.Failure(CodedError(ErrorCodes.UIA_UNAVAILABLE, "the helper could not start"))
        val s = state()
        s.start()
        s.awaitState("HelperMissing") { it is WindowsState.HelperMissing }
        s.stop()
        job.cancelChildren()
    }

    @Test
    fun `start lists the windows and goes Idle`() = kotlinx.coroutines.runBlocking {
        val s = state()
        s.start()
        s.awaitState("Idle") { it is WindowsState.Idle }
        assertEquals(listOf(notepad, elevated), s.ui.value.windows)
        s.stop()
        job.cancelChildren()
    }

    @Test
    fun `choose window captures, builds the index and selects nothing by default`() = kotlinx.coroutines.runBlocking {
        val s = state()
        s.start()
        s.awaitState("Idle") { it is WindowsState.Idle }
        s.chooseWindow(notepad.hwnd)
        s.awaitState("Captured") { it is WindowsState.Captured }
        assertTrue(s.ui.value.capture != null)
        assertTrue(s.ui.value.index!!.size >= 2)
        assertEquals(null, s.ui.value.selected)
        s.stop()
        job.cancelChildren()
    }

    @Test
    fun `a capture that fails with WINDOW_GONE keeps the last capture and says the window is gone`() = kotlinx.coroutines.runBlocking {
        val s = state()
        s.start()
        s.awaitState("Idle") { it is WindowsState.Idle }
        s.chooseWindow(notepad.hwnd)
        s.awaitState("Captured") { it is WindowsState.Captured }
        port.captureResults.addLast(Outcome.Failure(CodedError(ErrorCodes.WINDOW_GONE, "the window was closed")))
        s.refresh()
        s.awaitState("WindowGone") { it is WindowsState.WindowGone }
        assertTrue(s.ui.value.capture != null, "the last capture stays on screen")
        s.stop()
        job.cancelChildren()
    }

    @Test
    fun `a helper restart mid-capture surfaces the failure and the next capture succeeds`() = kotlinx.coroutines.runBlocking {
        val s = state()
        s.start()
        s.awaitState("Idle") { it is WindowsState.Idle }
        s.chooseWindow(notepad.hwnd)
        s.awaitState("Captured") { it is WindowsState.Captured }
        port.captureResults.addLast(Outcome.Failure(CodedError(ErrorCodes.UIA_UNAVAILABLE, "the helper was restarted")))
        s.refresh()
        s.awaitState("CaptureFailed") { it is WindowsState.CaptureFailed }
        port.captureResults.addLast(Outcome.Success(FakePort.screenFor(notepad.hwnd)))
        s.refresh()
        s.awaitState("Captured again") { it is WindowsState.Captured }
        s.stop()
        job.cancelChildren()
    }

    @Test
    fun `an elevated target surfaces ELEVATED_TARGET`() = kotlinx.coroutines.runBlocking {
        val s = state()
        s.start()
        s.awaitState("Idle") { it is WindowsState.Idle }
        port.captureResults.addLast(Outcome.Success(FakePort.screenFor(notepad.hwnd)))
        s.chooseWindow(notepad.hwnd)
        s.awaitState("Captured") { it is WindowsState.Captured }
        port.captureResults.addLast(Outcome.Failure(CodedError(ErrorCodes.ELEVATED_TARGET, "runs as administrator")))
        s.refresh()
        s.awaitState("CaptureFailed") { it is WindowsState.CaptureFailed }
        assertEquals(ErrorCodes.ELEVATED_TARGET, (s.ui.value.state as WindowsState.CaptureFailed).error.code)
        s.stop()
        job.cancelChildren()
    }

    @Test
    fun `a second capture request while one runs is ignored`() = kotlinx.coroutines.runBlocking {
        val s = state()
        s.start()
        s.awaitState("Idle") { it is WindowsState.Idle }
        port.captureResults.addLast(Outcome.Success(FakePort.screenFor(notepad.hwnd)))
        port.captureResults.addLast(Outcome.Success(FakePort.screenFor(notepad.hwnd)))
        s.chooseWindow(notepad.hwnd)
        s.chooseWindow(elevated.hwnd) // single flight: ignored while the first capture runs
        s.awaitState("Captured") { it is WindowsState.Captured }
        assertEquals(notepad.hwnd, s.ui.value.selectedHwnd)
        s.stop()
        job.cancelChildren()
    }

    @Test
    fun `pick mode relocates the picked element in the captured tree`() = kotlinx.coroutines.runBlocking {
        val s = state()
        s.start()
        s.awaitState("Idle") { it is WindowsState.Idle }
        s.startPick()
        s.awaitState("Picking") { it is WindowsState.Picking }
        port.pickFeed.emit(
            PickEvent.Hover("Button - OK", PickedElement(null, "app:id/ok", "OK", "Button", Bounds(400, 400, 200, 200))),
        )
        waitFor("hover label") { s.ui.value.hoverLabel != null }
        port.pickFeed.emit(
            PickEvent.Result(notepad.hwnd, PickedElement(null, "app:id/ok", "OK", "Button", Bounds(400, 400, 200, 200))),
        )
        s.awaitState("Captured") { it is WindowsState.Captured }
        waitFor("the picked element is selected") { s.ui.value.selected != null }
        val selectedNode = s.ui.value.index!!.byPath.getValue(s.ui.value.selected!!)
        assertEquals("app:id/ok", selectedNode.identifier)
        assertEquals(1, port.stoppedPicks.get(), "pick mode is stopped after the pick")
        s.stop()
        job.cancelChildren()
    }

    @Test
    fun `a cancelled pick returns to Idle and announces itself`() = kotlinx.coroutines.runBlocking {
        val s = state()
        s.start()
        s.awaitState("Idle") { it is WindowsState.Idle }
        val events = CopyOnWriteArrayList<WindowsEvent>()
        val collector = scope.launch { s.events.collect { events.add(it) } }
        s.startPick()
        s.awaitState("Picking") { it is WindowsState.Picking }
        port.pickFeed.emit(PickEvent.Cancelled)
        s.awaitState("Idle after cancel") { it is WindowsState.Idle }
        waitFor("pick cancelled event") { events.contains(WindowsEvent.PickCancelled) }
        collector.cancel()
        s.stop()
        job.cancelChildren()
    }

    @Test
    fun `the hotkey event starts a pick when idle`() = kotlinx.coroutines.runBlocking {
        val s = state()
        s.start()
        s.awaitState("Idle") { it is WindowsState.Idle }
        port.pickFeed.emit(PickEvent.HotkeyPressed)
        s.awaitState("Picking via hotkey") { it is WindowsState.Picking }
        assertTrue(s.ui.value.picking)
        s.cancelPick()
        s.stop()
        job.cancelChildren()
    }

    @Test
    fun `refresh keeps the selection when the element still exists`() = kotlinx.coroutines.runBlocking {
        val s = state()
        s.start()
        s.awaitState("Idle") { it is WindowsState.Idle }
        s.chooseWindow(notepad.hwnd)
        s.awaitState("Captured") { it is WindowsState.Captured }
        val selectedPath = ElementPath.root(0).child(0)
        s.select(selectedPath)
        port.captureResults.addLast(Outcome.Success(FakePort.screenFor(notepad.hwnd)))
        s.refresh()
        s.awaitState("Captured again") { it is WindowsState.Captured }
        assertEquals(selectedPath, s.ui.value.selected)
        s.stop()
        job.cancelChildren()
    }

    @Test
    fun `no port work ever runs on the main thread`() = kotlinx.coroutines.runBlocking {
        val s = state()
        s.start()
        s.awaitState("Idle") { it is WindowsState.Idle }
        s.chooseWindow(notepad.hwnd)
        s.awaitState("Captured") { it is WindowsState.Captured }
        s.refresh()
        s.awaitState("Captured again") { it is WindowsState.Captured }
        s.startPick()
        s.awaitState("Picking") { it is WindowsState.Picking }
        s.cancelPick()
        s.awaitState("Idle after pick") { it is WindowsState.Idle }
        assertTrue(
            port.threads.none { it.startsWith("ui-main") },
            "port calls on the main thread: ${port.threads.filter { it.startsWith("ui-main") }.size}",
        )
        assertTrue(port.threads.all { it.startsWith("ui-io") || it.startsWith("ui-default") }, port.threads.toSet().toString())
        s.stop()
        job.cancelChildren()
    }
}
