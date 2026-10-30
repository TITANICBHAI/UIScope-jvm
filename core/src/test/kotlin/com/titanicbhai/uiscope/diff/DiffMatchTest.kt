package com.titanicbhai.uiscope.diff

import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementFlags
import com.titanicbhai.uiscope.testing.N
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.capture
import com.titanicbhai.uiscope.testing.TestTrees.n
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/** G12 (plan Appendix E/G, regression B5): the old Diff saw **0 nodes** in a normal tree; the rebuilt engine must match them. */
class DiffMatchTest {

    private fun diff(before: N, after: N) = DiffMatch.diff(capture(listOf(before), id = "a"), capture(listOf(after), id = "b"))

    @Test
    fun `a normal tree with and without identifiers is fully matched, not seen as zero nodes`() {
        val tree = n(identifier = "app:id/root", children = listOf(
            n(className = "android.view.ViewGroup", children = listOf(
                n(className = "android.widget.Button", identifier = "app:id/ok", text = "OK"),
                n(className = "android.widget.TextView", text = "no id here"),
                n(className = "android.widget.Button", identifier = "app:id/ok", text = "OK 2"),
            )),
        ))
        val result = diff(tree, tree)
        assertEquals(0, result.added.size, "nothing is added by an identical tree")
        assertEquals(0, result.removed.size, "nothing is removed by an identical tree")
        assertEquals(5, result.pairs.size, "every node of the tree is matched")
        assertEquals(listOf<FieldChange>(), result.pairs.single { it.before.text == "no id here" }.changes)
    }

    @Test
    fun `nested trees match by path`() {
        val tree = n(identifier = "app:id/root", children = listOf(
            n(identifier = "app:id/child", children = listOf(n(identifier = "app:id/leaf", text = "leaf"))),
        ))
        val result = diff(tree, tree)
        assertEquals(3, result.pairs.size)
        assertEquals(result.pairs.map { it.pass }, List(3) { MatchPass.PATH })
    }

    @Test
    fun `a node that moved deeper is still found by identifier and occurrence`() {
        val moved = n(className = "android.widget.TextView", identifier = "app:id/moved", text = "same")
        val before = n(children = listOf(moved))
        val after = n(children = listOf(n(className = "android.view.ViewGroup", children = listOf(moved))))
        val result = diff(before, after)
        assertEquals(0, result.removed.size)
        val pair = result.pairs.single()
        assertEquals(MatchPass.IDENTIFIER, pair.pass)
        assertEquals("app:id/moved", pair.after.identifier)
        assertEquals(1, result.added.size, "the new wrapper is added")
    }

    @Test
    fun `duplicate ids that swap positions are matched by class and text`() {
        val before = n(children = listOf(
            n(className = "android.widget.TextView", identifier = "app:id/row", text = "alpha"),
            n(className = "android.widget.TextView", identifier = "app:id/row", text = "beta"),
        ))
        val after = n(children = listOf(
            n(className = "android.widget.TextView", identifier = "app:id/row", text = "beta"),
            n(className = "android.widget.TextView", identifier = "app:id/row", text = "alpha"),
        ))
        val result = diff(before, after)
        assertEquals(0, result.added.size)
        assertEquals(0, result.removed.size)
        assertEquals(2, result.pairs.size)
        assertEquals(result.pairs.map { it.pass }, List(2) { MatchPass.CONTENT_AND_PARENT })
        assertEquals(result.pairs.map { it.changes }, List(2) { emptyList<FieldChange>() })
    }

    @Test
    fun `a text change is reported on the matched pair`() {
        val result = diff(n(text = "old"), n(text = "new"))
        assertEquals(listOf(FieldChange("text", "old", "new")), result.pairs.single().changes)
    }

    @Test
    fun `a bounds change is reported as one change`() {
        val result = diff(n(bounds = Bounds(0, 0, 100, 100)), n(bounds = Bounds(40, 60, 100, 80)))
        assertEquals(listOf(FieldChange("bounds", "0,0 100x100", "40,60 100x80")), result.pairs.single().changes)
    }

    @Test
    fun `flag, property, description and name changes are reported in a fixed order`() {
        val before = n(properties = mapOf("keep" to "1", "drop" to "x", "change" to "old"))
        val after = n(
            name = "Name",
            description = "desc",
            flags = ElementFlags(clickable = true, checked = true),
            properties = mapOf("keep" to "1", "change" to "new", "add" to "y"),
        )
        val changes = diff(before, after).pairs.single().changes
        assertEquals(
            listOf(
                FieldChange("name", null, "Name"),
                FieldChange("description", null, "desc"),
                FieldChange("flags.clickable", "false", "true"),
                FieldChange("flags.checked", "null", "true"),
                FieldChange("properties.add", null, "y"),
                FieldChange("properties.change", "old", "new"),
                FieldChange("properties.drop", "x", null),
            ),
            changes,
        )
    }

    @Test
    fun `an inserted list item becomes one added node`() {
        fun item(i: Int) = n(className = "android.widget.TextView", identifier = "app:id/item", text = "item $i", bounds = Bounds(0, i * 40, 100, 40))
        val before = n(children = (0 until 3).map(::item))
        val after = n(children = listOf(item(0), item(1), item(2), n(className = "android.widget.TextView", identifier = "app:id/item", text = "item NEW", bounds = Bounds(0, 999, 100, 40))))
        val result = diff(before, after)
        assertEquals(0, result.removed.size)
        assertEquals(1, result.added.size)
        assertEquals("item NEW", result.added.single().text)
        assertEquals(4, result.pairs.size)
    }

    @Test
    fun `a removed list item becomes one removed node`() {
        fun item(i: Int) = n(className = "android.widget.TextView", identifier = "app:id/item", text = "item $i", bounds = Bounds(0, i * 40, 100, 40))
        val before = n(children = (0 until 3).map(::item))
        val after = n(children = (0 until 2).map(::item))
        val result = diff(before, after)
        assertEquals(0, result.added.size)
        assertEquals(1, result.removed.size)
        assertEquals("item 2", result.removed.single().text)
        assertEquals(3, result.pairs.size)
    }

    @Test
    fun `windows-style captures with Name and AutomationId are supported`() {
        val before = n(className = "Window", children = listOf(
            n(className = "Button", identifier = "CloseButton", name = "Close", properties = mapOf("ControlType" to "Button")),
        ))
        val after = n(className = "Window", children = listOf(
            n(className = "Button", identifier = "CloseButton", name = "Close (Admin)", properties = mapOf("ControlType" to "Button")),
        ))
        val result = diff(before, after)
        assertEquals(2, result.pairs.size)
        assertEquals(listOf(FieldChange("name", "Close", "Close (Admin)")), result.pairs[1].changes)
    }

    @Test
    fun `3k vs 3k nodes diff in under 500 ms`() {
        val after = shift(TestTrees.balanced(3000), 5, 5)
        val elapsed = measureTime { DiffMatch.diff(capture(listOf(TestTrees.balanced(3000)), id = "a"), capture(listOf(after), id = "b")) }
        assertTrue(elapsed.inWholeMilliseconds < 500, "diff took $elapsed")
    }

    private fun shift(node: N, dx: Int, dy: Int): N = N(
        node.className, node.identifier, node.text, node.description, node.name,
        node.bounds.translate(dx, dy), node.flags, node.properties, node.children.map { shift(it, dx, dy) },
    )
}
