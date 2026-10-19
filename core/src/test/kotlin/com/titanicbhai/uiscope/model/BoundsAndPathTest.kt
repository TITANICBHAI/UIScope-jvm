package com.titanicbhai.uiscope.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundsAndPathTest {
    @Test
    fun `edges are half open and empty bounds contain nothing`() {
        val b = Bounds(10, 20, 100, 50)
        assertEquals(110, b.right)
        assertEquals(70, b.bottom)
        assertTrue(b.contains(10, 20))
        assertTrue(b.contains(109, 69))
        assertFalse(b.contains(110, 69))
        assertFalse(b.contains(109, 70))
        assertTrue(b.contains(109.99, 69.99))
        assertFalse(Bounds.EMPTY.contains(0, 0))
        assertTrue(Bounds(5, 5, 0, 10).isEmpty)
        assertEquals(0L, Bounds(5, 5, 0, 10).area)
        assertEquals(5000L, b.area)
    }

    @Test
    fun `area does not overflow for huge rectangles`() {
        assertEquals(100_000L * 100_000L, Bounds(0, 0, 100_000, 100_000).area)
    }

    @Test
    fun `intersection containment and overlap ratio`() {
        val a = Bounds(0, 0, 100, 100)
        val b = Bounds(50, 50, 100, 100)
        assertEquals(Bounds(50, 50, 50, 50), a.intersection(b))
        assertNull(a.intersection(Bounds(100, 0, 10, 10)), "touching edges do not intersect")
        assertTrue(a.contains(Bounds(10, 10, 20, 20)))
        assertFalse(a.contains(b))
        assertEquals(2500.0 / 17500.0, a.intersectionOverUnion(b), 1e-12)
        assertEquals(1.0, a.intersectionOverUnion(a), 0.0)
        assertEquals(0.0, a.intersectionOverUnion(Bounds(500, 500, 10, 10)), 0.0)
        assertEquals(Bounds(5, -5, 100, 100), a.translate(5, -5))
        assertEquals(Bounds(1, 2, 3, 4), Bounds.fromEdges(1, 2, 4, 6))
    }

    @Test
    fun `negative coordinates are valid`() {
        val monitorLeftOfPrimary = Bounds(-1920, 100, 800, 600)
        assertTrue(monitorLeftOfPrimary.contains(-1500, 200))
        assertEquals(-1120, monitorLeftOfPrimary.right)
    }

    @Test
    fun `element paths are structural`() {
        val p = ElementPath("0/3/1")
        assertEquals(2, p.depth)
        assertEquals(1, p.siblingIndex)
        assertEquals(ElementPath("0/3"), p.parent)
        assertNull(ElementPath("4").parent)
        assertEquals(ElementPath("0/3/1/7"), p.child(7))
        assertTrue(ElementPath("0/3").isAncestorOf(p))
        assertFalse(p.isAncestorOf(p))
        assertFalse(ElementPath("0/3").isAncestorOf(ElementPath("0/31")), "prefix of the digits is not an ancestor")
        assertEquals("0/3/1", p.toString())
    }

    @Test
    fun `paths that could carry bounds are rejected`() {
        listOf("", "/", "0//1", "0/a", "0:10:20", "[0,0][10,10]", "0/1/").forEach {
            assertFailsWith<IllegalArgumentException>(it) { ElementPath(it) }
        }
    }
}
