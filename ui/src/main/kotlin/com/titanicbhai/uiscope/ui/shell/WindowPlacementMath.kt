package com.titanicbhai.uiscope.ui.shell

/**
 * RED STUB (plan P4-2, regression M10): reproduces the old app's behaviour — a fixed 1280×800 window whatever the screen,
 * no minimum size, and a saved position that is never clamped. The tests fail against this on their assertions.
 */
object WindowPlacementMath {
    const val MIN_WIDTH = 960
    const val MIN_HEIGHT = 600
    const val DEFAULT_MAX_WIDTH = 1280
    const val DEFAULT_MAX_HEIGHT = 800
    const val WORK_AREA_FRACTION = 0.9

    /** Default = max(minimum, min(1280×800 dp, 90 % of the work area)). */
    fun defaultSize(workWidth: Int, workHeight: Int): Pair<Int, Int> = DEFAULT_MAX_WIDTH to DEFAULT_MAX_HEIGHT

    /** Keeps the window on the visible work area (a 100 dp sliver of the title bar stays reachable). */
    fun clamp(x: Int, y: Int, width: Int, height: Int, workX: Int, workY: Int, workWidth: Int, workHeight: Int): Pair<Int, Int> = x to y

    /** Builds the geometry to open with from the saved values (null = default), clamped to the work area. */
    fun restore(
        savedWidth: Int?,
        savedHeight: Int?,
        savedX: Int?,
        savedY: Int?,
        savedMaximized: Boolean,
        workWidth: Int,
        workHeight: Int,
    ): WindowSpec = WindowSpec(savedWidth ?: DEFAULT_MAX_WIDTH, savedHeight ?: DEFAULT_MAX_HEIGHT, savedX, savedY, savedMaximized)
}

/** The window geometry to open with. [x]/[y] null = let the platform place the window. */
data class WindowSpec(val width: Int, val height: Int, val x: Int?, val y: Int?, val maximized: Boolean)
