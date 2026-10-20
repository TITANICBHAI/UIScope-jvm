package com.titanicbhai.uiscope.model

/**
 * Which element is under a point (capture space). Z-order aware (plan §4.5): iterate in reverse paint order, so later siblings
 * (dialogs, overlays) win over what they cover and a child wins over its parent, and take the first element containing the point.
 * Empty (zero-size) elements never match.
 */
object HitTest {
    fun hit(index: ElementIndex, x: Double, y: Double): ElementNode? =
        index.nodes.asReversed().firstOrNull { it.bounds.contains(x, y) }

    fun hit(index: ElementIndex, x: Int, y: Int): ElementNode? = hit(index, x.toDouble(), y.toDouble())
}
