package com.titanicbhai.uiscope.ui.shell

/**
 * Window placement (plan §4.1, M10): the default opens at min(1280×800 dp, 90 % of the work area) but never below the
 * 960×600 minimum; saved sizes below the minimum are raised; a saved position is clamped to the work area so a
 * 100 dp sliver of the title bar stays reachable (multi-monitor work areas arrive with Appendix K Round 2).
 */
object WindowPlacementMath {
    const val MIN_WIDTH = 960
    const val MIN_HEIGHT = 600
    const val DEFAULT_MAX_WIDTH = 1280
    const val DEFAULT_MAX_HEIGHT = 800
    const val WORK_AREA_FRACTION = 0.9

    private const val VISIBLE_SLIVER = 100

    /** Default = max(minimum, min(1280×800 dp, 90 % of the work area)). */
    fun defaultSize(workWidth: Int, workHeight: Int): Pair<Int, Int> {
        val width = minOf(DEFAULT_MAX_WIDTH, (workWidth * WORK_AREA_FRACTION).toInt())
        val height = minOf(DEFAULT_MAX_HEIGHT, (workHeight * WORK_AREA_FRACTION).toInt())
        return maxOf(MIN_WIDTH, width) to maxOf(MIN_HEIGHT, height)
    }

    /** Keeps the window on the visible work area; a window at least as large as it sits at the work area origin. */
    fun clamp(x: Int, y: Int, width: Int, height: Int, workX: Int, workY: Int, workWidth: Int, workHeight: Int): Pair<Int, Int> {
        val minX = if (width >= workWidth) workX else workX - width + VISIBLE_SLIVER
        val maxX = if (width >= workWidth) workX else workX + workWidth - VISIBLE_SLIVER
        val minY = if (height >= workHeight) workY else workY - height + VISIBLE_SLIVER
        val maxY = if (height >= workHeight) workY else workY + workHeight - VISIBLE_SLIVER
        return x.coerceIn(minX, maxX) to y.coerceIn(minY, maxY)
    }

    /** Builds the geometry to open with from the saved values (null = default), clamped to the work area. */
    fun restore(
        savedWidth: Int?,
        savedHeight: Int?,
        savedX: Int?,
        savedY: Int?,
        savedMaximized: Boolean,
        workWidth: Int,
        workHeight: Int,
    ): WindowSpec {
        val (defaultWidth, defaultHeight) = defaultSize(workWidth, workHeight)
        val width = (savedWidth ?: defaultWidth).coerceAtLeast(MIN_WIDTH)
        val height = (savedHeight ?: defaultHeight).coerceAtLeast(MIN_HEIGHT)
        val position = if (savedX != null && savedY != null) clamp(savedX, savedY, width, height, 0, 0, workWidth, workHeight) else null
        return WindowSpec(width, height, position?.first, position?.second, savedMaximized)
    }
}

/** The window geometry to open with. [x]/[y] null = let the platform place the window. */
data class WindowSpec(val width: Int, val height: Int, val x: Int?, val y: Int?, val maximized: Boolean)
