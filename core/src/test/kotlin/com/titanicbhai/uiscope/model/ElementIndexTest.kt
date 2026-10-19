package com.titanicbhai.uiscope.model

import com.titanicbhai.uiscope.testing.FakeClock
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.capture
import com.titanicbhai.uiscope.testing.TestTrees.n
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ElementIndexTest {
    private val sample = capture(
        listOf(
            n(
                "A",
                identifier = "id/a",
                children = listOf(
                    n("A1", text = "same", description = "d"),
                    n("A2", identifier = "id/dup", children = listOf(n("A2a", identifier = "id/dup", text = "same"))),
                ),
            ),
            n("B", name = "Pane", text = "same"),
        ),
    )
    private val index = ElementIndex(sample)

    @Test
    fun `nodes are listed in paint order`() {
        assertEquals(listOf("A", "A1", "A2", "A2a", "B"), index.nodes.map { it.className })
        assertEquals(listOf("0", "0/0", "0/1", "0/1/0", "1"), index.nodes.map { it.path.value })
        assertEquals(5, index.size)
        // reversed iteration = later siblings first, deepest before its ancestors
        assertEquals(listOf("B", "A2a", "A2", "A1", "A"), index.nodes.asReversed().map { it.className })
    }

    @Test
    fun `lookup by path parent and ancestors`() {
        val a2a = index.byPath.getValue(ElementPath("0/1/0"))
        assertEquals("A2a", a2a.className)
        assertEquals("A2", index.parentOf(a2a)?.className)
        assertEquals("A2", index.parentOf(ElementPath("0/1/0"))?.className)
        assertEquals(listOf("A", "A2"), index.ancestors(a2a).map { it.className }, "root first")
        assertNull(index.parentOf(index.byPath.getValue(ElementPath("1"))))
        assertEquals(emptyList(), index.ancestors(index.byPath.getValue(ElementPath("0"))))
    }

    @Test
    fun `occurrence counts by attribute ignore blanks`() {
        assertEquals(1, index.count(Attribute.IDENTIFIER, "id/a"))
        assertEquals(2, index.count(Attribute.IDENTIFIER, "id/dup"))
        assertEquals(3, index.count(Attribute.TEXT, "same"))
        assertEquals(1, index.count(Attribute.DESCRIPTION, "d"))
        assertEquals(1, index.count(Attribute.NAME, "Pane"))
        assertEquals(1, index.count(Attribute.CLASS, "A2a"))
        assertEquals(0, index.count(Attribute.TEXT, "missing"))
        assertEquals(0, index.count(Attribute.IDENTIFIER, ""))
        assertEquals(listOf("A2", "A2a"), index.nodesWith(Attribute.IDENTIFIER, "id/dup").map { it.className })
    }

    @Test
    fun `duplicate identifier badge`() {
        assertTrue(index.hasDuplicateIdentifier(index.byPath.getValue(ElementPath("0/1"))))
        assertFalse(index.hasDuplicateIdentifier(index.byPath.getValue(ElementPath("0"))))
        assertFalse(index.hasDuplicateIdentifier(index.byPath.getValue(ElementPath("1"))), "no identifier at all")
    }

    @Test
    fun `identity records the occurrence among nodes with the same identifier`() {
        assertEquals(0, index.identityOf(index.byPath.getValue(ElementPath("0/1"))).identifierOccurrence)
        assertEquals(1, index.identityOf(index.byPath.getValue(ElementPath("0/1/0"))).identifierOccurrence)
    }

    @Test
    fun `duplicate paths are rejected loudly`() {
        val broken = sample.copy(roots = sample.roots + sample.roots.first())
        assertFailsWith<IllegalArgumentException> { ElementIndex(broken) }
    }

    @Test
    fun `an empty capture has an empty index`() {
        val empty = ElementIndex(sample.copy(roots = emptyList()))
        assertEquals(0, empty.size)
        assertTrue(empty.byPath.isEmpty())
    }

    @Test
    fun `a deep tree does not overflow the stack`() {
        var node = n("leaf")
        repeat(1_500) { node = n("level", children = listOf(node)) }
        val deep = ElementIndex(capture(node))
        assertEquals(1_501, deep.size)
        assertEquals(1_500, deep.nodes.last().path.depth)
        assertEquals(1_500, deep.ancestors(deep.nodes.last()).size)
    }

    @Test
    fun `capture ids and timestamps come from the clock`() {
        val clock = FakeClock()
        val first = capture(listOf(n()), clock = clock)
        clock.advance(Duration.ofSeconds(5))
        val second = capture(listOf(n()), clock = clock)
        assertEquals(Duration.ofSeconds(5), Duration.between(first.takenAt, second.takenAt))
        assertSame(first, ElementIndex(first).capture)
    }

    @Test
    fun `index of 10k nodes builds in under 100 ms`() {
        val big = TestTrees.capture(TestTrees.balanced(10_000))
        repeat(3) { ElementIndex(big) } // warm-up: JIT and class loading are not what is measured
        val best = (1..5).minOf { measureMillis { assertEquals(10_000, ElementIndex(big).size) } }
        assertTrue(best < 100.0, "building the index took $best ms (limit 100 ms)")
    }

    private inline fun measureMillis(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000.0
    }
}
