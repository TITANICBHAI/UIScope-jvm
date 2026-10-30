package com.titanicbhai.uiscope.ui.inspect

import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.capture
import com.titanicbhai.uiscope.testing.TestTrees.n
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure tree-navigation math of P4-4 (plan §4.5): search, visible rows, keyboard movement. */
class TreeNavigationTest {

    private fun tree(): TestTrees.N = n(identifier = "app:id/root", children = listOf(
        n(className = "android.widget.TextView", identifier = "app:id/a", text = "Alpha"),
        n(className = "android.widget.Button", identifier = "app:id/b", text = "beta value"),
        n(className = "android.view.ViewGroup", children = listOf(
            n(className = "android.widget.TextView", text = "gamma child"),
        )),
    ))

    private fun index() = com.titanicbhai.uiscope.model.ElementIndex(capture(tree(), id = "t"))

    @Test
    fun `visible rows only descend into expanded subtrees`() {
        val idx = index()
        val rootPath = ElementPath.root(0)
        val childPath = rootPath.child(2)
        assertEquals(1, visibleRows(idx, emptySet()).size, "collapsed: only the root")
        assertEquals(4, visibleRows(idx, setOf(rootPath)).size, "root expanded: all four rows")
        assertEquals(2, visibleRows(idx, setOf(childPath)).size, "expanding the collapsed child adds only its own row")
    }

    @Test
    fun `search matches text, identifier and class case-insensitively`() {
        val idx = index()
        assertEquals(1, searchTree(idx, "alpha").size, "text match, case-insensitive")
        assertEquals(1, searchTree(idx, "app:id/b").size, "identifier match")
        assertEquals(1, searchTree(idx, "viewgroup").size, "class match")
        assertEquals(0, searchTree(idx, "   ").size, "a blank query matches nothing")
        assertEquals(0, searchTree(idx, "missing").size)
    }

    @Test
    fun `search over 10k nodes takes under 100 ms`() {
        val idx = com.titanicbhai.uiscope.model.ElementIndex(capture(TestTrees.balanced(10_000), id = "big"))
        val elapsed = measureTimeMillis { searchTree(idx, "text 50") }
        assertTrue(elapsed < 100, "search took $elapsed ms, the plan budgets 100 ms")
    }

    @Test
    fun `keyboard movement walks the visible rows and clamps at the ends`() {
        val idx = index()
        val root = ElementPath.root(0)
        val rows = visibleRows(idx, setOf(root))
        assertEquals(root, moveSelection(rows, null, 1), "from nothing, down selects the first row")
        assertEquals(root, moveSelection(rows, root, -1), "up from the first row stays")
        assertEquals(root.child(2), moveSelection(rows, root, 1))
        assertEquals(root, moveSelection(rows, root.child(2), -1))
        assertEquals(rows.last().path, moveSelection(rows, root, 50), "way past the end clamps to the last row")
        assertEquals(null, moveSelection(emptyList(), null, 1), "nothing visible, nothing selected")
    }
}
