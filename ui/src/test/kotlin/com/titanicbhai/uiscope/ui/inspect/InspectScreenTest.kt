package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.model.AdbDevice
import com.titanicbhai.uiscope.model.DeviceState
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.ports.AdbStatus
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

    private class Recording {
        var started = 0
        var captured = 0
        val selectedDevices = ArrayList<String>()
        val selectedPaths = ArrayList<String?>()
        var live: Boolean? = null
    }

    private fun recordingCallbacks(r: Recording) = InspectCallbacks(
        onStart = { r.started++ },
        onSelectDevice = { r.selectedDevices += it },
        onCapture = { r.captured++ },
        onSetLive = { r.live = it },
        onSelect = { r.selectedPaths += it?.toString() },
    )

    private fun uiWithCapture(): AndroidInspectUi {
        val cap = capture(
            n(identifier = "app:id/root", children = listOf(
                n(className = "android.widget.TextView", identifier = "app:id/label", text = "Hello"),
            )),
            id = "cap-1",
        )
        val device = AdbDevice("SERIAL1", DeviceState.DEVICE, "Pixel 7", "p", "1")
        return AndroidInspectUi(
            state = AndroidState.Captured(device, cap),
            adb = AdbStatus("adb", "1.0.41", "PATH"),
            devices = listOf(device),
            selectedSerial = "SERIAL1",
            capture = cap,
            index = ElementIndex(cap),
            expanded = setOf(ElementPath.root(0)),
            live = LiveState(enabled = true),
        )
    }

    private fun setContent(ui: AndroidInspectUi, target: InspectTarget = InspectTarget.ANDROID, windowsPc: Boolean = false): Recording {
        val r = Recording()
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                InspectScreen(ui = ui, target = target, windowsPcAvailable = windowsPc, callbacks = recordingCallbacks(r))
            }
        }
        return r
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
        val r = setContent(uiWithCapture())
        rule.onNodeWithText("Hello").assertIsDisplayed()
        rule.onNodeWithText("Hello").performClick()
        rule.waitForIdle()
        assertEquals(listOf<String?>("0/0"), r.selectedPaths)
    }

    @Test
    fun `capture and live controls report through the callbacks`() {
        val r = setContent(uiWithCapture())
        rule.onNodeWithText("Capture now").performClick()
        rule.waitForIdle()
        assertEquals(1, r.captured)
        rule.onNodeWithText("Live").performClick()
        rule.waitForIdle()
        assertEquals(false, r.live)
    }

    @Test
    fun `an adb-missing state shows the full-screen error with its primary action`() {
        val ui = AndroidInspectUi(state = AndroidState.AdbMissing)
        val r = setContent(ui)
        rule.onNodeWithText("ADB not found").assertIsDisplayed()
        rule.onNodeWithText(Strings.ACTION_DOWNLOAD_ADB).performClick()
        rule.waitForIdle()
        assertEquals(1, r.started)
    }

    @Test
    fun `the device picker lists every device and selection reports the serial`() {
        val base = uiWithCapture()
        val ui = base.copy(devices = base.devices + AdbDevice("SERIAL2", DeviceState.DEVICE, "Emulator", "e", "2"))
        val r = setContent(ui)
        rule.onNodeWithText("Pixel 7 · …IAL1", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Emulator · …IAL2", substring = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("SERIAL2"), r.selectedDevices)
    }
}
