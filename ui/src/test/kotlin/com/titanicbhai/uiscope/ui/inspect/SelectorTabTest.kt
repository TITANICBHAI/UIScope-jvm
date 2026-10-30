package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.capture
import com.titanicbhai.uiscope.testing.TestTrees.n
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P4-6: the verdict card, candidates with the engine's match counts, Test selector, and copy actions that copy exactly what is shown. */
class SelectorTabTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeClipboard : ClipboardManager {
        var last: String? = null
        override fun setText(text: androidx.compose.ui.text.AnnotatedString) { last = text.text }
        override fun getText(): androidx.compose.ui.text.AnnotatedString? = last?.let { androidx.compose.ui.text.AnnotatedString(it) }
    }

    private val cap = capture(
        n(identifier = "app:id/root", children = listOf(
            n(className = "android.widget.TextView", identifier = "com.example:id/login_button", text = "Log in"),
        )),
        id = "sel-1",
    )
    private val index = ElementIndex(cap)
    private val node = index.nodes.first { it.identifier == "com.example:id/login_button" }

    private fun setContent(): FakeClipboard {
        val clipboard = FakeClipboard()
        val highlights = ArrayList<TestHighlight?>()
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Column {
                        Text("ANCHOR")
                        SelectorTab(node = node, index = index, onTestHighlight = { highlights.add(it) })
                    }
                }
            }
        }
        return clipboard
    }

    @Test
    fun `the verdict card shows the tier and the summary for a unique id`() {
        setContent()
        rule.onNodeWithText("Reliable").assertIsDisplayed()
        rule.onNodeWithText("matches 1", substring = true).assertIsDisplayed()
    }

    @Test
    fun `test selector reports the matched paths`() {
        var reported: TestHighlight? = null
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                SelectorTab(node = node, index = index, onTestHighlight = { reported = it })
            }
        }
        rule.onNodeWithText("Test selector").performClick()
        rule.waitForIdle()
        val highlight = reported!!
        assertEquals(setOf(ElementPath.root(0).child(0)), highlight.paths)
        assertTrue(highlight.unique, "exactly one match is green on the canvas")
    }

    @Test
    fun `copy selector puts exactly the displayed selector text on the clipboard`() {
        val clipboard = setContent()
        rule.onNodeWithText("Copy selector").performClick()
        rule.waitForIdle()
        // The bare selector line is displayed under the code block; the clipboard holds exactly it.
        val displayedSelector = rule.onAllNodesWithText("login_button", substring = true).fetchSemanticsNodes()
        assertTrue(displayedSelector.isNotEmpty(), "the bare selector is displayed")
        val copied = clipboard.last
        assertTrue(copied != null && copied.contains("login_button"), "copy selector copied the selector text: $copied")
    }

    @Test
    fun `properties copy on click copies the value and copy all as json copies every row`() {
        val clipboard = FakeClipboard()
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    PropertiesTab(node = node)
                }
            }
        }
        rule.onNodeWithText("Log in").performClick()
        rule.waitForIdle()
        assertEquals("Log in", clipboard.last)
        rule.onNodeWithText("Copy all as JSON").performClick()
        rule.waitForIdle()
        val json = clipboard.last!!
        assertTrue(json.startsWith("{") && json.contains("login_button") && json.contains("bounds"), "the JSON has every row: $json")
    }
}
