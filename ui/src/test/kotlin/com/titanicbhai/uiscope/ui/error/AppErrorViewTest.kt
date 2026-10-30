package com.titanicbhai.uiscope.ui.error

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.ui.Strings
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import java.lang.reflect.Modifier as JavaModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** M11 (plan Appendix G): errors must be visible BESIDE content (the old UI showed errors only when the tree was empty). */
class AppErrorViewTest {

    @get:org.junit.Rule
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

    @Test
    fun `every error code of the implementation and of Appendix A has copy`() {
        val codes = ErrorCodes::class.java.declaredFields
            .filter { it.type == String::class.java && JavaModifier.isStatic(it.modifiers) }
            .map { it.get(null) as String } + listOf(
            "UIA_UNAVAILABLE", "ELEVATED_TARGET", "WINDOW_GONE", "WINDOW_MINIMIZED", "LIMITED_ACCESSIBILITY", "TREE_TRUNCATED", "HOTKEY_IN_USE",
        )
        // Appendix A marks these two with no primary action ("-"); every other code has one.
        val withoutAction = setOf(ErrorCodes.SNAPSHOT_IMPORT_INVALID, ErrorCodes.CAPTURE_SCHEMA_UNSUPPORTED)
        for (code in codes.distinct()) {
            val copy = Strings.error(code)
            assertTrue(copy.title.isNotBlank(), "$code has no title")
            assertTrue(copy.what.isNotBlank(), "$code has no explanation")
            if (code !in withoutAction) assertNotNull(copy.action, "$code has no primary action")
            assertEquals(copy, Strings.error(code), "$code lookup is not stable")
        }
        assertEquals(Strings.error("NO_SUCH_CODE_MADE_UP"), Strings.error(ErrorCodes.UNEXPECTED), "an unknown code falls back to UNEXPECTED")
    }
}
