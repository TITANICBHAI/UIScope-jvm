package com.titanicbhai.uiscope.model

/**
 * Child-index path of a node inside its capture, for example `"0/3/1"` = first root, its fourth child, that node's second child.
 * Unique within one capture and derived only from tree structure: **bounds never appear in it** (fixes M2).
 */
@JvmInline
value class ElementPath(val value: String) {
    init {
        require(value.isNotEmpty() && value.split('/').all { part -> part.isNotEmpty() && part.all { it in '0'..'9' } }) {
            "Invalid element path '$value': expected child indexes separated by '/', for example 0/3/1"
        }
    }

    /** Number of ancestors: a root has depth 0. */
    val depth: Int get() = value.count { it == '/' }

    /** Position among its siblings. */
    val siblingIndex: Int get() = value.substringAfterLast('/').toInt()

    val parent: ElementPath? get() = value.lastIndexOf('/').takeIf { it >= 0 }?.let { ElementPath(value.substring(0, it)) }

    fun child(index: Int): ElementPath = ElementPath("$value/$index")

    fun isAncestorOf(other: ElementPath): Boolean = other.value.startsWith("$value/")

    override fun toString(): String = value

    companion object {
        fun root(index: Int = 0): ElementPath = ElementPath(index.toString())
    }
}
