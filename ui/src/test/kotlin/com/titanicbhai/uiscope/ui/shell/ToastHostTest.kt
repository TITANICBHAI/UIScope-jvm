package com.titanicbhai.uiscope.ui.shell

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import org.junit.Rule
import org.junit.Test

/** §4.1: informational toasts auto-dismiss after 3 s; error toasts persist until dismissed. */
class ToastHostTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `an informational toast disappears after three seconds`() {
        val state = ToastState()
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                Text("CONTENT")
                ToastHost(state)
            }
        }
        rule.mainClock.autoAdvance = false
        state.show("Snapshot saved")
        rule.waitForIdle()
        rule.onNodeWithText("Snapshot saved").assertExists()
        rule.mainClock.advanceTimeBy(3_100)
        rule.waitForIdle()
        rule.onNodeWithText("Snapshot saved").assertDoesNotExist()
    }

    @Test
    fun `an error toast stays until it is dismissed`() {
        val state = ToastState()
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                ToastHost(state)
            }
        }
        rule.mainClock.autoAdvance = false
        state.showError("The screen can't be read right now")
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(60_000)
        rule.waitForIdle()
        rule.onNodeWithText("The screen can't be read right now").assertExists()
        state.dismiss()
        rule.waitForIdle()
        rule.onNodeWithText("The screen can't be read right now").assertDoesNotExist()
    }
}
