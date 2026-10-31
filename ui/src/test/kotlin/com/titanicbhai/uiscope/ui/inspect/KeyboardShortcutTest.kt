@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.titanicbhai.uiscope.model.AdbDevice
import com.titanicbhai.uiscope.model.DeviceState
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.ports.AdbStatus
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.state.AndroidInspectUi
import com.titanicbhai.uiscope.state.AndroidState
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.capture
import com.titanicbhai.uiscope.testing.TestTrees.n
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P4-9: the 4.11 shortcuts fire from the Inspect screen (no single-letter shortcuts). */
class KeyboardShortcutTest {

    @get:Rule
    val rule = createComposeRule()

    private class Recording {
        var captured = 0
        var live: Boolean? = null
        var cancelled = 0
    }

    private fun contentUi(): AndroidInspectUi {
        val cap = capture(
            n(identifier = "app:id/root", children = listOf(
                n(className = "android.widget.TextView", identifier = "com.example:id/login_button", text = "Log in"),
            )),
            id = "kbd-1",
        )
        return AndroidInspectUi(
            state = AndroidState.Captured(AdbDevice("SERIAL1", DeviceState.DEVICE, "Pixel 7", "p", "1"), cap),
            adb = AdbStatus("adb", "1.0.41", "PATH"),
            capture = cap,
            index = ElementIndex(cap),
            expanded = setOf(ElementPath.root(0)),
            selected = ElementPath.root(0).child(0),
            live = com.titanicbhai.uiscope.state.LiveState(enabled = true),
        )
    }

    private fun setContent(live: Boolean = true): Recording {
        val r = Recording()
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                InspectScreen(
                    ui = contentUi().copy(live = com.titanicbhai.uiscope.state.LiveState(enabled = live)),
                    target = com.titanicbhai.uiscope.settings.InspectTarget.ANDROID,
                    windowsPcAvailable = false,
                    callbacks = InspectCallbacks(
                        onCapture = { r.captured++ },
                        onSetLive = { r.live = it },
                        onCancelCapture = { r.cancelled++ },
                    ),
                )
            }
        }
        return r
    }

    @Test
    fun `F5 captures and ctrl plus L toggles Live`() {
        val r = setContent()
        rule.onNodeWithTag("uiscope.inspect").performKeyInput { pressKey(Key.F5) }
        rule.waitForIdle()
        assertEquals(1, r.captured)
        rule.onNodeWithTag("uiscope.inspect").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.L)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
        assertEquals(false, r.live)
    }

    @Test
    fun `ctrl shift plus C copies the best selector expression`() {
        val clipboard = object : androidx.compose.ui.platform.ClipboardManager {
            var last: String? = null
            override fun setText(text: androidx.compose.ui.text.AnnotatedString) { last = text.text }
            override fun getText(): androidx.compose.ui.text.AnnotatedString? = last?.let { androidx.compose.ui.text.AnnotatedString(it) }
        }
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalClipboardManager provides clipboard) {
                    InspectScreen(
                        ui = contentUi(),
                        target = com.titanicbhai.uiscope.settings.InspectTarget.ANDROID,
                        windowsPcAvailable = false,
                        callbacks = InspectCallbacks(),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("uiscope.inspect").performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.ShiftLeft)
            pressKey(Key.C)
            keyUp(Key.ShiftLeft)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
        val copied = clipboard.last
        assertTrue(copied != null && copied.contains("login_button"), "the best selector is on the clipboard: $copied")
    }
}
