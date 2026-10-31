package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.CaptureWarning
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.model.ScreenInfo
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.model.WindowInfo as ModelWindowInfo
import com.titanicbhai.uiscope.ports.WindowInfo
import com.titanicbhai.uiscope.settings.InspectTarget
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.state.AndroidInspectUi
import com.titanicbhai.uiscope.state.WindowsInspectUi
import com.titanicbhai.uiscope.state.WindowsState
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.ui.Strings
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** P4-3: the Windows target renders from the immutable [WindowsInspectUi] snapshot plus callbacks only - no engine. */
class WindowsInspectScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val notepad = WindowInfo("0x00120A3C", "Untitled - Notepad", "notepad.exe", 1234, false)
    private val taskmgr = WindowInfo("0x0000BEEF", "Task Manager", "Taskmgr.exe", 99, true)

    private class Recording {
        var started = 0
        var picks = 0
        val pickedWindows = ArrayList<String>()
        val selectedPaths = ArrayList<String?>()
    }

    private fun capture(): Capture = TestTrees.capture(
        roots = listOf(
            TestTrees.n(
                className = "Window",
                identifier = "app:id/root",
                name = "Root",
                bounds = Bounds(0, 0, 800, 600),
                children = listOf(
                    TestTrees.n(className = "Button", identifier = "app:id/ok", name = "OK", bounds = Bounds(400, 400, 200, 60)),
                ),
            ),
        ),
        id = "win-cap-1",
        screen = ScreenInfo(width = 800, height = 600, originX = 100, originY = 50),
        target = Target.Windows(
            ModelWindowInfo(title = "Untitled - Notepad", processName = "notepad.exe", pid = 1234, isElevated = false, hwnd = "0x00120a3c"),
        ),
    )

    private fun uiWith(state: WindowsState, capture: Capture? = null): WindowsInspectUi = WindowsInspectUi(
        state = state,
        windows = listOf(notepad, taskmgr),
        selectedHwnd = capture?.let { (it.target as Target.Windows).window.hwnd },
        capture = capture,
        index = capture?.let { ElementIndex(it) },
        expanded = if (capture == null) emptySet() else setOf(ElementPath.root(0)),
    )

    private fun setContent(windows: WindowsInspectUi): Recording {
        val r = Recording()
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                InspectScreen(
                    ui = AndroidInspectUi(),
                    target = InspectTarget.WINDOWS,
                    windowsPcAvailable = true,
                    callbacks = InspectCallbacks(),
                    windows = windows,
                    windowsCallbacks = WindowsCallbacks(
                        onStart = { r.started++ },
                        onSelectWindow = { r.pickedWindows += it },
                        onPick = { r.picks++ },
                        onSelect = { r.selectedPaths += it?.toString() },
                    ),
                )
            }
        }
        return r
    }

    @Test
    fun `a helper-missing state shows the full-screen error with its retry action`() {
        val r = setContent(uiWith(WindowsState.HelperMissing(CodedError(ErrorCodes.UIA_UNAVAILABLE))))
        rule.onNodeWithText("Windows inspection isn't available").assertIsDisplayed()
        rule.onNodeWithText(Strings.ACTION_RETRY).performClick()
        rule.waitForIdle()
        assertEquals(1, r.started)
    }

    @Test
    fun `the window picker lists every window with its elevation and reports the choice`() {
        val r = setContent(uiWith(WindowsState.Idle))
        rule.onNodeWithText("Choose window...").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Untitled - Notepad - notepad.exe").performClick()
        rule.waitForIdle()
        assertEquals(listOf("0x00120A3C"), r.pickedWindows)
    }

    @Test
    fun `the pick button reports through the callback`() {
        val r = setContent(uiWith(WindowsState.Idle))
        rule.onNodeWithText("Pick element").performClick()
        rule.waitForIdle()
        assertEquals(1, r.picks)
    }

    @Test
    fun `picking shows the hover label instead of the pick button`() {
        setContent(uiWith(WindowsState.Picking).copy(picking = true, hoverLabel = "Button - OK"))
        rule.onNodeWithText("Button - OK").assertIsDisplayed()
    }

    @Test
    fun `picking without a hover yet tells the user how to cancel`() {
        setContent(uiWith(WindowsState.Picking).copy(picking = true))
        rule.onNodeWithText("Pick an element - Esc or right-click cancels").assertIsDisplayed()
    }

    @Test
    fun `the workspace shows the capture tree and a click selects`() {
        val cap = capture()
        val r = setContent(uiWith(WindowsState.Captured(notepad, cap), capture = cap))
        rule.onNodeWithText("OK").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        rule.waitForIdle()
        assertEquals(listOf<String?>("0/0"), r.selectedPaths)
    }

    @Test
    fun `capture warnings render their copy over the workspace`() {
        val cap = capture().copy(warnings = listOf(CaptureWarning(ErrorCodes.LIMITED_ACCESSIBILITY)))
        setContent(uiWith(WindowsState.Captured(notepad, cap), capture = cap))
        rule.onNodeWithText("This app exposes little accessibility information").assertIsDisplayed()
    }
}
