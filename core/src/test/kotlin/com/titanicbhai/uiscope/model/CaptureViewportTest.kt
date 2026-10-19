package com.titanicbhai.uiscope.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Appendix G6 (fixes B3's math): a window at screen (300,120), non-zero origin, several scales and zooms. */
class CaptureViewportTest {
    private val originX = 300.0
    private val originY = 120.0
    private val delta = 0.5

    private fun assertNear(expected: Double, actual: Double, tolerance: Double = delta, message: String = "") =
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "$message expected $expected but was $actual")

    @Test
    fun `absolute bounds map to canvas coordinates by subtracting the origin`() {
        val viewport = CaptureViewport(originX, originY, scale = 1.5)
        val rect = viewport.toCanvas(Bounds(350, 170, 100, 40))
        assertEquals(RectD(75.0, 75.0, 225.0, 135.0), rect)
        assertEquals(150.0, rect.width)
        assertEquals(60.0, rect.height)
        // the window's own top-left corner lands on the canvas origin
        assertEquals(PointD(0.0, 0.0), viewport.toCanvas(300.0, 120.0))
    }

    @Test
    fun `pan and zoom are applied after the scale`() {
        val viewport = CaptureViewport(originX, originY, scale = 2.0, zoom = 1.5, panX = 10.0, panY = -20.0)
        assertEquals(PointD((350 - 300) * 3.0 + 10.0, (170 - 120) * 3.0 - 20.0), viewport.toCanvas(350.0, 170.0))
    }

    @Test
    fun `round trip within half a pixel at every scale and zoom with a non-zero origin`() {
        val bounds = listOf(Bounds(300, 120, 800, 600), Bounds(350, 170, 100, 40), Bounds(1099, 719, 1, 1), Bounds(-1920, 500, 300, 200))
        for (scale in listOf(1.0, 1.25, 1.5, 2.0)) {
            for (zoom in listOf(1.0, 2.5, 0.4)) {
                val viewport = CaptureViewport(originX, originY, scale, zoom, panX = 17.5, panY = -9.25)
                for (b in bounds) {
                    val canvas = viewport.toCanvas(b)
                    val topLeft = viewport.toCapture(canvas.left, canvas.top)
                    val bottomRight = viewport.toCapture(canvas.right, canvas.bottom)
                    val tag = "scale=$scale zoom=$zoom $b:"
                    assertNear(b.x.toDouble(), topLeft.x, message = tag)
                    assertNear(b.y.toDouble(), topLeft.y, message = tag)
                    assertNear(b.right.toDouble(), bottomRight.x, message = tag)
                    assertNear(b.bottom.toDouble(), bottomRight.y, message = tag)
                }
            }
        }
    }

    @Test
    fun `zoom is anchored at the cursor`() {
        val start = CaptureViewport(originX, originY, scale = 1.25, zoom = 1.0, panX = 30.0, panY = 12.0)
        val cursor = PointD(400.0, 300.0)
        val under = start.toCapture(cursor.x, cursor.y)
        for (zoom in listOf(0.5, 2.0, 3.0, 10.0)) {
            val zoomed = start.zoomAt(cursor.x, cursor.y, zoom)
            assertEquals(zoom, zoomed.zoom)
            val after = zoomed.toCapture(cursor.x, cursor.y)
            assertNear(under.x, after.x, 1e-9, "zoom=$zoom x")
            assertNear(under.y, after.y, 1e-9, "zoom=$zoom y")
        }
        val stepped = start.zoomBy(cursor.x, cursor.y, 1.1).zoomBy(cursor.x, cursor.y, 1.1)
        assertNear(1.21, stepped.zoom, 1e-12)
        assertNear(under.x, stepped.toCapture(cursor.x, cursor.y).x, 1e-9)
    }

    @Test
    fun `zoom is clamped and the anchor still holds at the limits`() {
        val start = CaptureViewport(originX, originY, scale = 1.0)
        val cursor = PointD(250.0, 90.0)
        val under = start.toCapture(cursor.x, cursor.y)
        val max = start.zoomAt(cursor.x, cursor.y, 1_000.0)
        val min = start.zoomAt(cursor.x, cursor.y, 0.0001)
        assertEquals(CaptureViewport.MAX_ZOOM, max.zoom)
        assertEquals(CaptureViewport.MIN_ZOOM, min.zoom)
        assertNear(under.x, max.toCapture(cursor.x, cursor.y).x, 1e-9)
        assertNear(under.y, min.toCapture(cursor.x, cursor.y).y, 1e-9)
    }

    @Test
    fun `fit centres the screenshot and keeps the origin`() {
        val phone = CaptureViewport.fit(ScreenInfo(1080, 2400), 600.0, 600.0)
        assertEquals(0.25, phone.scale)
        assertEquals(165.0, phone.panX)
        assertEquals(0.0, phone.panY)

        val window = CaptureViewport.fit(ScreenInfo(800, 600, originX = 300, originY = 120), 1000.0, 500.0)
        assertEquals(300.0, window.originX)
        assertEquals(120.0, window.originY)
        assertNear(500.0 / 600.0, window.scale, 1e-12)
        // the window's top-left corner is drawn at the pan offset, its bottom-right inside the canvas
        val topLeft = window.toCanvas(300.0, 120.0)
        val bottomRight = window.toCanvas(1100.0, 720.0)
        assertNear(window.panX, topLeft.x, 1e-9)
        assertNear(0.0, topLeft.y, 1e-9)
        assertNear(500.0, bottomRight.y, 1e-9)
        assertTrue(bottomRight.x <= 1000.0 + 1e-9)
    }

    @Test
    fun `fit is safe for degenerate sizes`() {
        assertEquals(1.0, CaptureViewport.fit(ScreenInfo(0, 0), 600.0, 400.0).scale)
        assertEquals(1.0, CaptureViewport.fit(ScreenInfo(100, 100), 0.0, 0.0).scale)
    }

    @Test
    fun `actual size is one canvas pixel per screenshot pixel and stays centred`() {
        val screen = ScreenInfo(1080, 2400)
        val fitted = CaptureViewport.fit(screen, 600.0, 600.0)
        val actual = fitted.actualSize(screen, 600.0, 600.0)
        assertNear(1.0, actual.effectiveScale, 1e-12)
        val centre = actual.toCanvas(540.0, 1200.0)
        assertNear(300.0, centre.x, 1e-9)
        assertNear(300.0, centre.y, 1e-9)
    }

    @Test
    fun `panBy moves the picture without changing the scale`() {
        val moved = CaptureViewport(originX, originY, 1.0).panBy(15.0, -5.0)
        assertEquals(PointD(15.0, -5.0), moved.toCanvas(300.0, 120.0))
        assertEquals(1.0, moved.effectiveScale)
    }
}
