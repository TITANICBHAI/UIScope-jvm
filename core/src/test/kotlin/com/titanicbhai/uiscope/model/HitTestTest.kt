package com.titanicbhai.uiscope.model

import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.capture
import com.titanicbhai.uiscope.testing.TestTrees.n
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** G11 (plan Appendix G, regression M13): hit-testing is z-order aware - the topmost containing element wins, deepest before parents. */
class HitTestTest {

    private fun g11Capture() = capture(listOf(
        n(className = "android.widget.FrameLayout", bounds = Bounds(0, 0, 1080, 2400), children = listOf(
            n(className = "android.widget.TextView", text = "under the dialog", bounds = Bounds(200, 1000, 400, 100)),
        )),
        n(className = "android.widget.Dialog", bounds = Bounds(100, 900, 600, 400), children = listOf(
            n(className = "android.widget.Button", text = "OK", bounds = Bounds(200, 1000, 120, 60)),
        )),
    ), id = "g11")

    @Test
    fun `a dialog painted over an activity wins at a shared point`() {
        val hit = HitTest.hit(ElementIndex(g11Capture()), 510.0, 1010.0)
        // The activity's TextView is under the point but painted earlier; the dialog (later root) covers it.
        assertEquals("android.widget.Dialog", hit?.className)
    }

    @Test
    fun `the deepest element inside the dialog wins over the dialog itself`() {
        val hit = HitTest.hit(ElementIndex(g11Capture()), 250.0, 1010.0)
        assertEquals("android.widget.Button", hit?.className)
    }

    @Test
    fun `identical overlays resolve to the topmost one`() {
        val cap = capture(listOf(
            n(className = "android.widget.TextView", text = "first", bounds = Bounds(0, 0, 100, 100)),
            n(className = "android.widget.TextView", text = "second", bounds = Bounds(0, 0, 100, 100)),
        ), id = "g11-overlays")
        val hit = HitTest.hit(ElementIndex(cap), 50.0, 50.0)
        assertEquals("second", hit?.text)
    }

    @Test
    fun `empty elements never match`() {
        val cap = capture(listOf(
            n(className = "android.widget.FrameLayout", bounds = Bounds(0, 0, 100, 100), children = listOf(
                n(className = "android.widget.TextView", text = "not laid out", bounds = Bounds(50, 50, 0, 0)),
            )),
        ), id = "g11-empty")
        val hit = HitTest.hit(ElementIndex(cap), 50.0, 50.0)
        assertEquals("android.widget.FrameLayout", hit?.className)
    }

    @Test
    fun `a point inside the activity root selects it and a point outside everything selects nothing`() {
        val index = ElementIndex(g11Capture())
        assertEquals("android.widget.FrameLayout", HitTest.hit(index, 5.0, 5.0)?.className)
        assertNull(HitTest.hit(index, -1.0, -1.0))
    }
}
