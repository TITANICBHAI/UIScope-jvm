package com.titanicbhai.uiscope.model

import kotlin.math.min

data class PointD(val x: Double, val y: Double)

data class RectD(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
}

/**
 * All coordinate math between **capture space** (element [Bounds], absolute) and **canvas space** (pixels of the drawing area).
 * Pure and immutable: every change returns a new viewport. No UI code is allowed to do this math itself (plan §4.5).
 *
 * `canvas = (capture − origin) × scale × zoom + pan`
 *
 * @property originX,originY Where the screenshot's top-left corner sits in capture space ([ScreenInfo.originX]/[ScreenInfo.originY]).
 * @property scale Fit factor: canvas pixels per screenshot pixel at zoom 1 (set by [fit]).
 * @property zoom User zoom on top of [scale], within [MIN_ZOOM]..[MAX_ZOOM].
 * @property panX,panY Canvas position of the screenshot's top-left corner.
 */
data class CaptureViewport(
    val originX: Double,
    val originY: Double,
    val scale: Double,
    val zoom: Double = 1.0,
    val panX: Double = 0.0,
    val panY: Double = 0.0,
) {
    /** Canvas pixels per screenshot pixel right now. */
    val effectiveScale: Double get() = scale * zoom

    fun toCanvas(captureX: Double, captureY: Double): PointD =
        PointD((captureX - originX) * effectiveScale + panX, (captureY - originY) * effectiveScale + panY)

    fun toCanvas(bounds: Bounds): RectD {
        val topLeft = toCanvas(bounds.x.toDouble(), bounds.y.toDouble())
        val bottomRight = toCanvas(bounds.right.toDouble(), bounds.bottom.toDouble())
        return RectD(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
    }

    fun toCapture(canvasX: Double, canvasY: Double): PointD =
        PointD((canvasX - panX) / effectiveScale + originX, (canvasY - panY) / effectiveScale + originY)

    /** Sets the zoom to [newZoom] (clamped) keeping the capture point under ([cursorX], [cursorY]) exactly where it is. */
    fun zoomAt(cursorX: Double, cursorY: Double, newZoom: Double): CaptureViewport {
        val clamped = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val ratio = clamped / zoom
        return copy(zoom = clamped, panX = cursorX - (cursorX - panX) * ratio, panY = cursorY - (cursorY - panY) * ratio)
    }

    /** Multiplies the zoom by [factor], anchored at the cursor (one wheel step). */
    fun zoomBy(cursorX: Double, cursorY: Double, factor: Double): CaptureViewport = zoomAt(cursorX, cursorY, zoom * factor)

    fun panBy(dx: Double, dy: Double): CaptureViewport = copy(panX = panX + dx, panY = panY + dy)

    /** The "100 %" view: one screenshot pixel per canvas pixel, with the screenshot's centre kept at the canvas centre. */
    fun actualSize(screen: ScreenInfo, canvasWidth: Double, canvasHeight: Double): CaptureViewport {
        val target = (1.0 / scale).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val s = scale * target
        return copy(
            zoom = target,
            panX = (canvasWidth - screen.width * s) / 2.0,
            panY = (canvasHeight - screen.height * s) / 2.0,
        )
    }

    companion object {
        const val MIN_ZOOM = 0.1
        const val MAX_ZOOM = 32.0

        /** The default view: the whole screenshot fitted into the canvas and centred. */
        fun fit(screen: ScreenInfo, canvasWidth: Double, canvasHeight: Double): CaptureViewport {
            val scale = if (screen.width > 0 && screen.height > 0 && canvasWidth > 0 && canvasHeight > 0) {
                min(canvasWidth / screen.width, canvasHeight / screen.height)
            } else {
                1.0
            }
            return CaptureViewport(
                originX = screen.originX.toDouble(),
                originY = screen.originY.toDouble(),
                scale = scale,
                zoom = 1.0,
                panX = (canvasWidth - screen.width * scale) / 2.0,
                panY = (canvasHeight - screen.height * scale) / 2.0,
            )
        }
    }
}
