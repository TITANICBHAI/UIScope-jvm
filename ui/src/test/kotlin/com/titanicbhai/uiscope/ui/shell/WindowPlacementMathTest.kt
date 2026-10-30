package com.titanicbhai.uiscope.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M10 (plan Appendix G/§2.2): the old window was a fixed 1280×800 with no minimum and no clamping; the shell must do better. */
class WindowPlacementMathTest {

    @Test
    fun `default size on a large screen is capped at 1280x800`() {
        assertEquals(1280 to 800, WindowPlacementMath.defaultSize(1920, 1080))
    }

    @Test
    fun `default size on a small screen shrinks to 90 percent of the work area`() {
        assertEquals(960 to 691, WindowPlacementMath.defaultSize(1024, 768))
    }

    @Test
    fun `default size never goes below the 960x600 minimum`() {
        assertEquals(960 to 600, WindowPlacementMath.defaultSize(800, 600))
    }

    @Test
    fun `saved sizes smaller than the minimum are raised to it`() {
        val spec = WindowPlacementMath.restore(800, 500, 0, 0, false, workWidth = 1920, workHeight = 1080)
        assertEquals(960, spec.width)
        assertEquals(600, spec.height)
    }

    @Test
    fun `without saved values the default size and platform placement are used`() {
        val spec = WindowPlacementMath.restore(null, null, null, null, false, workWidth = 1920, workHeight = 1080)
        assertEquals(WindowSpec(1280, 800, null, null, maximized = false), spec)
    }

    @Test
    fun `a position far off the screen is clamped so the title bar stays reachable`() {
        val (x, y) = WindowPlacementMath.clamp(5000, 5000, 1280, 800, workX = 0, workY = 0, workWidth = 1920, workHeight = 1080)
        assertTrue(x <= 1920 - 100, "x=$x leaves no sliver of the window visible")
        assertTrue(y <= 1080 - 100, "y=$y leaves no sliver of the window visible")
    }

    @Test
    fun `a negative position is clamped so the title bar stays reachable`() {
        val (x, y) = WindowPlacementMath.clamp(-5000, -5000, 1280, 800, workX = 0, workY = 0, workWidth = 1920, workHeight = 1080)
        assertTrue(x >= -1280 + 100, "x=$x puts the whole window off the screen")
        assertTrue(y >= -800 + 100, "y=$y puts the whole window off the screen")
    }

    @Test
    fun `a window wider than the screen has its x pinned to the work area origin`() {
        val (x, y) = WindowPlacementMath.clamp(100, 100, 2500, 800, workX = 0, workY = 0, workWidth = 1920, workHeight = 1080)
        assertEquals(0, x)
        assertEquals(100, y)
    }

    @Test
    fun `the maximized flag survives a restore`() {
        assertTrue(WindowPlacementMath.restore(1280, 800, 10, 10, true, 1920, 1080).maximized)
    }
}
