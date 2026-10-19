package com.titanicbhai.uiscope.model

/**
 * A rectangle in **capture space**: the coordinates the target reports.
 * Android: device pixels, origin (0,0). Windows: physical screen pixels (the window may sit anywhere, even at negative x/y).
 * Edges are half-open: a point on [right]/[bottom] is outside.
 */
data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    /** Zero or negative size. Android reports `[0,0][0,0]` for views that are not laid out. */
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    val area: Long get() = if (isEmpty) 0L else width.toLong() * height.toLong()
    val centerX: Double get() = x + width / 2.0
    val centerY: Double get() = y + height / 2.0

    fun contains(px: Double, py: Double): Boolean = !isEmpty && px >= x && px < right && py >= y && py < bottom

    fun contains(px: Int, py: Int): Boolean = contains(px.toDouble(), py.toDouble())

    /** True when [other] lies completely inside this rectangle. */
    fun contains(other: Bounds): Boolean =
        !isEmpty && !other.isEmpty && other.x >= x && other.y >= y && other.right <= right && other.bottom <= bottom

    fun intersects(other: Bounds): Boolean =
        !isEmpty && !other.isEmpty && x < other.right && other.x < right && y < other.bottom && other.y < bottom

    fun intersection(other: Bounds): Bounds? {
        if (!intersects(other)) return null
        return fromEdges(maxOf(x, other.x), maxOf(y, other.y), minOf(right, other.right), minOf(bottom, other.bottom))
    }

    /** Intersection area divided by union area, 0.0 for disjoint or empty rectangles. Used to match elements across captures. */
    fun intersectionOverUnion(other: Bounds): Double {
        val overlap = intersection(other)?.area ?: return 0.0
        val union = area + other.area - overlap
        return if (union <= 0L) 0.0 else overlap.toDouble() / union.toDouble()
    }

    fun translate(dx: Int, dy: Int): Bounds = copy(x = x + dx, y = y + dy)

    companion object {
        val EMPTY = Bounds(0, 0, 0, 0)

        fun fromEdges(left: Int, top: Int, right: Int, bottom: Int): Bounds = Bounds(left, top, right - left, bottom - top)
    }
}
