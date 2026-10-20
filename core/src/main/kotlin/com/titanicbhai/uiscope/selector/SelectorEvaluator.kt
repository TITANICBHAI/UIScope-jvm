package com.titanicbhai.uiscope.selector

import com.titanicbhai.uiscope.model.Attribute
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.HitTest

/** Runs a neutral [Selector] against a capture. This is what "matches N in this capture" means everywhere in the app. */
object SelectorEvaluator {
    /** The elements selected, in document (paint) order and without duplicates. */
    fun evaluate(index: ElementIndex, selector: Selector): List<ElementNode> = when (selector) {
        is Selector.Attr -> matchAttr(index, selector)
        is Selector.Index -> listOfNotNull(matchAttr(index, selector.base).getOrNull(selector.index))
        is Selector.Anchor -> matchAttr(index, selector.anchor)
            .mapNotNull { anchor -> followPath(anchor, selector.relativePath) }
            .distinctBy { it.path }
        is Selector.Path -> listOfNotNull(index.byPath[selector.path])
        is Selector.Coordinates -> listOfNotNull(HitTest.hit(index, selector.x, selector.y))
    }

    fun count(index: ElementIndex, selector: Selector): Int = evaluate(index, selector).size

    /** The node's value for [key], or null when it has none. */
    fun valueOf(node: ElementNode, key: SelectorKey): String? = when (key) {
        SelectorKey.IDENTIFIER -> node.identifier
        SelectorKey.TEXT -> node.text
        SelectorKey.DESCRIPTION -> node.contentDescription
        SelectorKey.NAME -> node.name
        SelectorKey.CONTROL_TYPE -> node.properties["ControlType"]
        SelectorKey.CLASS -> node.className
    }

    private fun matchAttr(index: ElementIndex, attr: Selector.Attr): List<ElementNode> {
        // Start from the smallest pre-indexed group, then verify every condition.
        val start = attr.conditions
            .mapNotNull { condition -> attribute(condition.key)?.let { index.nodesWith(it, condition.value) } }
            .minByOrNull { it.size }
            ?: index.nodes
        return start.filter { node -> attr.conditions.all { valueOf(node, it.key) == it.value } }
    }

    private fun attribute(key: SelectorKey): Attribute? = when (key) {
        SelectorKey.IDENTIFIER -> Attribute.IDENTIFIER
        SelectorKey.TEXT -> Attribute.TEXT
        SelectorKey.DESCRIPTION -> Attribute.DESCRIPTION
        SelectorKey.NAME -> Attribute.NAME
        SelectorKey.CLASS -> Attribute.CLASS
        SelectorKey.CONTROL_TYPE -> null
    }

    private fun followPath(from: ElementNode, steps: List<Int>): ElementNode? {
        var current = from
        for (step in steps) current = current.children.getOrNull(step) ?: return null
        return current
    }
}
