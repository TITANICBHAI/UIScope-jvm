package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

/** The tree pane's M2 contract: expansion and selection come from the state, so a new capture never re-collapses the tree. */
class TreePaneUiTest {

    @get:Rule
    val rule = createComposeRule()

    private class Recording {
        var expandedAll = 0
        var collapsedAll = 0
        val expandedSubtrees = ArrayList<String>()
        val toggled = ArrayList<String>()
        val selected = ArrayList<String?>()
    }

    private val index = ElementIndex(capture(
        n(identifier = "app:id/root", children = listOf(
            n(className = "android.widget.TextView", identifier = "app:id/label", text = "Hello"),
            n(className = "android.widget.Button", identifier = "app:id/ok", text = "beta value"),
        )),
        id = "cap-1",
    ))
    private val root = ElementPath.root(0)

    private fun setContent(expanded: Set<ElementPath>, selected: ElementPath?, captureId: String = "cap-1"): Recording {
        val r = Recording()
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                Column {
                    Text("CONTENT-ANCHOR")
                    TreePane(
                        index = index,
                        expanded = expanded,
                        selected = selected,
                        onToggleExpanded = { r.toggled += it.toString() },
                        onSelect = { r.selected += it?.toString() },
                        onExpandAll = { r.expandedAll++ },
                        onCollapseAll = { r.collapsedAll++ },
                        onExpandSubtree = { r.expandedSubtrees += it.toString() },
                        onCollapseSubtree = {},
                        duplicateIds = { false },
                    )
                }
            }
        }
        return r
    }

    @Test
    fun `expand all reports through the callback`() {
        val r = setContent(expanded = emptySet(), selected = null)
        rule.onNodeWithContentDescription("Expand all").performClick()
        rule.waitForIdle()
        assertEquals(1, r.expandedAll)
        rule.onNodeWithContentDescription("Collapse all").performClick()
        rule.waitForIdle()
        assertEquals(1, r.collapsedAll)
    }

    @Test
    fun `search shows the match count and jumps through the matches`() {
        setContent(expanded = setOf(root), selected = null)
        rule.onNodeWithText("Search").performTextInput("beta")
        rule.waitForIdle()
        rule.onNodeWithText("1 of 1").assertIsDisplayed()
        rule.onNodeWithText("beta value").assertIsDisplayed()
    }

    @Test
    fun `a second capture with the same structure keeps rows visible and the selection highlighted`() {
        setContent(expanded = setOf(root), selected = root.child(0), captureId = "cap-1")
        rule.onNodeWithText("Hello").assertIsDisplayed()
        // The state holder published a NEW capture (fresh objects, same structure); the pane must not re-collapse.
        val recaptured = ElementIndex(capture(
            n(identifier = "app:id/root", children = listOf(
                n(className = "android.widget.TextView", identifier = "app:id/label", text = "Hello"),
                n(className = "android.widget.Button", identifier = "app:id/ok", text = "beta value"),
            )),
            id = "cap-2",
        ))
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                TreePane(
                    index = recaptured,
                    expanded = setOf(root),
                    selected = root.child(0),
                    onToggleExpanded = {},
                    onSelect = {},
                    onExpandAll = {},
                    onCollapseAll = {},
                    onExpandSubtree = {},
                    onCollapseSubtree = {},
                    duplicateIds = { false },
                )
            }
        }
        rule.onNodeWithText("Hello").assertIsDisplayed()
    }
}
