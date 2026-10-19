package com.titanicbhai.uiscope.model

import com.titanicbhai.uiscope.testing.TestTrees.capture
import com.titanicbhai.uiscope.testing.TestTrees.n
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Appendix G5 (fixes M2): path ids unchanged when bounds change; relocation by path/identity after a recapture. */
class ElementIdentityTest {
    private val pkg = "com.app"

    private fun screen(
        shift: Int = 0,
        extraFirst: Boolean = false,
        rowClass: String = "android.widget.TextView",
    ) = capture(
        listOf(
            n(
                "android.widget.FrameLayout",
                bounds = Bounds(shift, shift, 1080, 2400),
                children = listOf(
                    // An optional banner that pushes everything after it one position down (paths change).
                    *(if (extraFirst) arrayOf(n("android.widget.TextView", identifier = "$pkg:id/banner", text = "New!")) else emptyArray()),
                    n("android.widget.TextView", identifier = "$pkg:id/title", text = "Orders", bounds = Bounds(shift, shift + 63, 300, 80)),
                    n(
                        "androidx.recyclerview.widget.RecyclerView",
                        identifier = "$pkg:id/list",
                        bounds = Bounds(shift, 210 + shift, 1080, 2000),
                        children = (0 until 4).map { row ->
                            n(rowClass, identifier = "$pkg:id/row_title", text = "Order #${1001 + row}", bounds = Bounds(shift + 40, 210 + shift + row * 300, 600, 70))
                        },
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `paths are structural and never contain bounds`() {
        val before = ElementIndex(screen(shift = 0))
        val after = ElementIndex(screen(shift = 37))
        assertEquals(before.nodes.map { it.path }, after.nodes.map { it.path }, "moving everything must not change a single id")
        before.nodes.forEach { assertTrue(Regex("""\d+(/\d+)*""").matches(it.path.value), "unexpected path ${it.path}") }
        assertEquals(listOf("0", "0/0", "0/1", "0/1/0", "0/1/1", "0/1/2", "0/1/3"), before.nodes.map { it.path.value })
        assertTrue(before.nodes.map { it.bounds } != after.nodes.map { it.bounds }, "the bounds really did change")
    }

    @Test
    fun `identity resolves by path when only bounds changed`() {
        val before = ElementIndex(screen(shift = 0))
        val after = ElementIndex(screen(shift = 123))
        val title = before.byPath.getValue(ElementPath("0/0"))
        val found = assertNotNull(after.relocate(before.identityOf(title)))
        assertEquals(ElementPath("0/0"), found.path)
        assertEquals("Orders", found.text)
    }

    @Test
    fun `identity falls back to identifier plus occurrence when paths shift`() {
        val before = ElementIndex(screen())
        val after = ElementIndex(screen(extraFirst = true))
        val title = before.byPath.getValue(ElementPath("0/0"))
        assertEquals(ElementPath("0/1"), assertNotNull(after.relocate(before.identityOf(title))).path, "title moved down by one")

        // the third duplicate row_title is found again at the third occurrence, even though every path changed
        val thirdRow = before.byPath.getValue(ElementPath("0/1/2"))
        assertEquals(2, before.identityOf(thirdRow).identifierOccurrence)
        val relocated = assertNotNull(after.relocate(before.identityOf(thirdRow)))
        assertEquals(ElementPath("0/2/2"), relocated.path)
        assertEquals("Order #1003", relocated.text)
    }

    @Test
    fun `a path hit with a different class or identifier is not accepted`() {
        val before = ElementIndex(screen())
        val after = ElementIndex(screen(rowClass = "android.widget.Button"))
        val row = before.byPath.getValue(ElementPath("0/1/1"))
        assertNull(after.relocate(before.identityOf(row)), "same path and identifier but a different class is a different element")
    }

    @Test
    fun `an element that is gone is not found`() {
        val before = ElementIndex(screen())
        val gone = capture(n("android.widget.FrameLayout"))
        val title = before.byPath.getValue(ElementPath("0/0"))
        assertNull(ElementIndex(gone).relocate(before.identityOf(title)))
    }

    @Test
    fun `an element without an identifier is only found at its path`() {
        val before = ElementIndex(capture(n("android.view.View", children = listOf(n("android.view.View"), n("android.widget.TextView", text = "x")))))
        val node = before.byPath.getValue(ElementPath("0/1"))
        val identity = before.identityOf(node)
        assertNull(identity.identifier)
        assertEquals(ElementPath("0/1"), assertNotNull(before.relocate(identity)).path)
        val shifted = ElementIndex(capture(n("android.view.View", children = listOf(n("android.view.View"), n("android.view.View"), n("android.widget.TextView", text = "x")))))
        assertNull(shifted.relocate(identity), "no identifier, path now holds a different class")
    }
}
