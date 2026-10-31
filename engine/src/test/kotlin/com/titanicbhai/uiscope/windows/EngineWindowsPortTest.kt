package com.titanicbhai.uiscope.windows

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.CaptureViewport
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.ports.PickEvent
import com.titanicbhai.uiscope.process.SystemProcessLauncher
import com.titanicbhai.uiscope.testing.FakeProcess
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.awaitTrue
import com.titanicbhai.uiscope.testing.blocking
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** P3-3/P3-5/P3-7: the real port over real stdio — window list, capture with origin (P3-5), pick events (G6). */
class EngineWindowsPortTest {

    private val dispatchers: DispatcherProvider = StandardDispatcherProvider(kotlinx.coroutines.Dispatchers.Default)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + kotlinx.coroutines.Dispatchers.Default)

    private fun port(): EngineWindowsPort {
        val client = WindowsHelperClient(
            launcher = SystemProcessLauncher(dispatchers),
            helperCommand = FakeProcess.command("helper"),
            dispatchers = dispatchers,
            requestTimeout = 10.seconds,
            logger = java.util.logging.Logger.getLogger("test.port"),
        )
        return EngineWindowsPort(client, dispatchers = dispatchers)
    }

    @Test
    fun `listWindows returns the visible windows with elevation flags`() = blocking {
        val p = port()
        p.connect().assertSuccess()
        val windows = p.listWindows().assertSuccess()
        assertEquals(2, windows.size)
        assertEquals("notepad.exe", windows[0].processName)
        assertEquals(false, windows[0].isElevated)
        assertEquals("Taskmgr.exe", windows[1].processName)
        assertEquals(true, windows[1].isElevated)
        job.cancelChildren()
    }

    @Test
    fun `captureWindow builds a capture with origin and the mapped tree`() = blocking {
        val p = port()
        p.connect().assertSuccess()
        val capture = p.captureWindow("0x00120A3C").assertSuccess()
        val target = capture.target as Target.Windows
        assertEquals("0x00120a3c", target.window.hwnd)
        assertEquals("notepad.exe", target.window.processName)
        // P3-5: the origin travels in ScreenInfo so CaptureViewport does all the mapping.
        assertEquals(100, capture.screen.originX)
        assertEquals(50, capture.screen.originY)
        assertEquals(800, capture.screen.width)
        assertEquals(600, capture.screen.height)
        val shot = capture.screenshot
        assertTrue(shot != null && Files.exists(java.nio.file.Path.of(shot.file)),
            "the screenshot temp file exists")
        assertEquals(2, capture.roots.sumOf { 1 + it.children.size }, "root + mapped child")
        val child = capture.roots.single().children.single()
        assertEquals("app:id/ok", child.identifier)
        assertEquals("Button", child.properties["ControlType"])
        assertEquals(Bounds(500, 450, 200, 60), child.bounds)
        job.cancelChildren()
    }

    @Test
    fun `the mapped capture maps through CaptureViewport (G6 on helper fixtures)`() = blocking {
        val p = port()
        p.connect().assertSuccess()
        val capture = p.captureWindow("0x00120A3C").assertSuccess()
        val viewport = CaptureViewport.fit(capture.screen, 400.0, 300.0)
        // The button's capture-space centre must land on the canvas inside the fitted viewport.
        val button = capture.roots.single().children.single()
        val canvas = viewport.toCanvas(button.bounds)
        assertTrue(canvas.left >= 0 && canvas.right <= 400, "button fits on the canvas: $canvas")
        // And back: un-mapping the canvas centre of the button returns its capture-space centre (600, 480).
        val centre = viewport.toCapture((canvas.left + canvas.right) / 2, (canvas.top + canvas.bottom) / 2)
        assertEquals(600.0, centre.x, 0.5)
        assertEquals(480.0, centre.y, 0.5)
        job.cancelChildren()
    }

    @Test
    fun `pick events are translated to hover and result with the window handle`() = blocking {
        val p = port()
        p.connect().assertSuccess()
        // The helper emits the events while startPick is in flight, so the collector subscribes first
        // (the events SharedFlow has no replay: anything emitted before a subscriber joins is gone).
        val received = CopyOnWriteArrayList<PickEvent>()
        val collector = scope.launch { p.pickEvents().take(2).toList().let { received.addAll(it) } }
        p.startPick().assertSuccess()
        withTimeout(10.seconds) { awaitTrue(10_000, "hover + result received") { received.size >= 2 } }
        val hover = received[0]
        assertTrue(hover is PickEvent.Hover && hover.element.controlType == "Button", "hover translated: $hover")
        val result = received[1] as PickEvent.Result
        assertEquals("0x00120a3c", result.hwnd)
        assertEquals("app:id/ok", result.element.automationId)
        collector.cancel()
        job.cancelChildren()
    }

    @Test
    fun `untruncated captures carry no warnings`() = blocking {
        val p = port()
        p.connect().assertSuccess()
        val capture = p.captureWindow("0x00120A3C").assertSuccess()
        assertTrue(capture.warnings.isEmpty(), "the fixture is not truncated")
        job.cancelChildren()
    }
}
