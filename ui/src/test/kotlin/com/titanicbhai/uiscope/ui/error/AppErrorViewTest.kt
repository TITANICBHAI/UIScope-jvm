package com.titanicbhai.uiscope.ui.error

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.ui.Strings
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M11 (plan Appendix G): errors must be visible BESIDE content (the old UI showed errors only when the tree was empty). */
class AppErrorViewTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a banner shows the error copy and its action while the content next to it stays`() {
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                Column {
                    // The last capture's content: an error must never replace it.
                    Text("LAST CAPTURE STAYS")
                    AppErrorView(CodedError(ErrorCodes.DUMP_TIMEOUT), ErrorVariant.BANNER, onDismiss = {})
                }
            }
        }
        rule.onNodeWithText("Timed out reading the screen").assertIsDisplayed()
        rule.onNodeWithText("The device is slow or busy.").assertIsDisplayed()
        rule.onNodeWithText(Strings.ACTION_RETRY).assertIsDisplayed()
        rule.onNodeWithText(Strings.ACTION_COPY_DETAILS).assertExists()
        rule.onNodeWithText("LAST CAPTURE STAYS").assertIsDisplayed()
    }

    @Test
    fun `a full-screen error shows title, explanation and action`() {
        rule.setContent {
            UiScopeTheme(ThemeMode.DARK) {
                AppErrorView(CodedError(ErrorCodes.ADB_NOT_FOUND), ErrorVariant.FULL_SCREEN, onDismiss = {})
            }
        }
        rule.onNodeWithText("ADB not found").assertIsDisplayed()
        rule.onNodeWithText("UIScope needs Android's adb tool to talk to your device.", substring = true).assertIsDisplayed()
        rule.onNodeWithText(Strings.ACTION_DOWNLOAD_ADB).assertIsDisplayed()
        rule.onNodeWithText(Strings.ACTION_COPY_DETAILS).assertExists()
    }
}
