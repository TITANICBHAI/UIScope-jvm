package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasToggleAction
import androidx.compose.ui.test.performClick
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.settings.InspectTarget
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.state.AndroidInspectUi
import com.titanicbhai.uiscope.state.AndroidState
import com.titanicbhai.uiscope.state.LiveState
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.capture
import com.titanicbhai.uiscope.testing.TestTrees.n
import com.titanicbhai.uiscope.ui.Strings
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** P4-3: the Inspect screen renders from an immutable UI snapshot plus callbacks only — no engine anywhere (module graph, B9). */
class InspectScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private class Recording : InspectCallbacks() {
        var started = 0
        var captured = 0
        val selectedDevices = ArrayList<String>()
        val selectedPaths = ArrayList<String?>()
        val toggled = ArrayList<String>()
        var live: Boolean? = null

        override val onStart: () -> Unit = { started++ }
        override val onSelectDevice: (String) -> Unit = { selectedDevices += it }
        override val onCapture: () -> Unit = { captured++ }
        override val onSetLive: (Boolean) -> Unit = { live = it }
        override val onSelect: (com.titanicbhai.uiscope.model.ElementPath?) -> Unit = { selectedPaths += it?.toString() }
        override val onToggleExpanded: (com.titanicbhai.uiscope.model.ElementPath) -> Unit = { toggled += it.toString() }
    }

    private fun uiWithCapture(): AndroidInspectUi {
        val cap = capture(
            n(identifier = "app:id/root", children = listOf(
                n(className = "android.widget.TextView", identifier = "app:id/label", text = "Hello"),
            )),
            id = "cap-1",
        )
        return AndroidInspectUi(
            state = AndroidState.Captured(TestTrees.androidTarget(), cap),
            adb = com.titanicbhai.uiscope.model.AdbStatus("adb", "1.0.41", "PATH"),
            devices = listOf(com.titanicbhai.uiscope.model.AdbDevice("SERIAL1", com.titanicbhai.uiscope.model.DeviceState.DEVICE, "Pixel 7", "p", "1")),
            selectedSerial = "SERIAL1",
            capture = cap,
            index = ElementIndex(cap),
            expanded = setOf(com.titanicbhai.uiscope.model.ElementPath.root(0)),
            live = LiveState(enabled = true),
        )
    }

    private fun setContent(ui: AndroidInspectUi, target: InspectTarget = InspectTarget.ANDROID, windowsPc: Boolean = false): Recording {
        val callbacks = Recording()
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                InspectScreen(ui = ui, target = target, windowsPcAvailable = windowsPc, callbacks = callbacks)
            }
        }
        return callbacks
    }

    @Test
    fun `the pc target shows the explanatory empty state on a non-windows host`() {
        setContent(uiWithCapture(), target = InspectTarget.WINDOWS, windowsPc = false)
        rule.onNodeWithText(PC_TARGET_EMPTY_STATE).assertIsDisplayed()
    }

    @Test
    fun `a problem is shown as a banner while the capture stays`() {
        val ui = uiWithCapture().copy(problem = CodedError(ErrorCodes.SECURE_WINDOW))
        setContent(ui)
        rule.onNodeWithText("This screen may block capture").assertIsDisplayed()
        rule.onNodeWithText("Hello").assertIsDisplayed()
    }

    @Test
    fun `the tree shows capture content and clicking a row selects it`() {
        val callbacks = setContent(uiWithCapture())
        rule.onNodeWithText("Hello").assertIsDisplayed()
        rule.onNodeWithText("Hello").performClick()
        rule.waitForIdle()
        assertEquals(listOf("0/0"), callbacks.selectedPaths)
    }

    @Test
    fun `capture and live controls report through the callbacks`() {
        val callbacks = setContent(uiWithCapture())
        rule.onNodeWithText("Capture now").performClick()
        rule.waitForIdle()
        assertEquals(1, callbacks.captured)
        rule.onNode(hasToggleAction()).performClick()
        rule.waitForIdle()
        assertEquals(false, callbacks.live)
    }

    @Test
    fun `an adb-missing state shows the full-screen error with its primary action`() {
        val ui = AndroidInspectUi(state = AndroidState.AdbMissing)
        val callbacks = setContent(ui)
        rule.onNodeWithText("ADB not found").assertIsDisplayed()
        rule.onNodeWithText(Strings.ACTION_DOWNLOAD_ADB).assertIsDisplayed()
        rule.onNodeWithText(Strings.ACTION_DOWNLOAD_ADB).performClick()
        rule.waitForIdle()
        assertEquals(1, callbacks.started)
    }

    @Test
    fun `the device picker lists every device and selection reports the serial`() {
        val base = uiWithCapture()
        val ui = base.copy(devices = base.devices + com.titanicbhai.uiscope.model.AdbDevice("SERIAL2", com.titanicbhai.uiscope.model.DeviceState.DEVICE, "Emulator", "e", "2"))
        val callbacks = setContent(ui)
        rule.onNodeWithText("Pixel 7 · …RIAL1", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Emulator · …RIAL2", substring = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("SERIAL2"), callbacks.selectedDevices)
        rule.onAllNodesWithText("No devices").assertCountEquals(0)
    }
}
