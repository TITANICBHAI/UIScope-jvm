package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ScreenInfo
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.settings.ExportFormat
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import org.junit.Rule
import org.junit.Test

/** P5-2: the dialog renders the scope/format/redact controls and guards the PNG cases. */
class ExportDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private val capture = TestTrees.capture(
        roots = listOf(
            TestTrees.n(className = "android.widget.TextView", identifier = "app:id/a", text = "Hello", bounds = Bounds(0, 0, 100, 40)),
        ),
        id = "cap-dialog",
        screen = ScreenInfo(width = 800, height = 600),
    )

    private fun setContent(selected: com.titanicbhai.uiscope.model.ElementPath? = null) {
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                ExportDialog(
                    capture = capture,
                    index = com.titanicbhai.uiscope.model.ElementIndex(capture),
                    selected = selected,
                    defaultFormat = ExportFormat.JSON,
                    appVersion = "test",
                    actions = ExportActions(),
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun `png disables the clipboard and warns that screenshots cannot be redacted`() {
        setContent()
        rule.onNodeWithText("PNG").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Copy to clipboard").assertIsNotEnabled()
        rule.onNodeWithText("Redact text values").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Screenshots cannot be redacted - the PNG keeps the original text.").assertIsDisplayed()
    }

    @Test
    fun `the subtree scope is disabled without a selection and enabled with one`() {
        setContent(selected = null)
        rule.onNodeWithText("Selected subtree (select one first)").assertIsDisplayed()
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                ExportDialog(
                    capture = capture,
                    index = com.titanicbhai.uiscope.model.ElementIndex(capture),
                    selected = com.titanicbhai.uiscope.model.ElementPath.root(0).child(0),
                    defaultFormat = ExportFormat.OUTLINE,
                    appVersion = "test",
                    actions = ExportActions(),
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("Selected subtree").assertIsDisplayed()
    }
}
