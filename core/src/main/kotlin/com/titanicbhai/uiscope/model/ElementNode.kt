package com.titanicbhai.uiscope.model

/** State flags of an element. Android sets all of them; Windows maps what UI Automation provides (Appendix B). */
data class ElementFlags(
    val enabled: Boolean = true,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val checkable: Boolean = false,
    /** Null when the element has no checked state. */
    val checked: Boolean? = null,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val scrollable: Boolean = false,
    val selected: Boolean = false,
    val offscreen: Boolean = false,
    val password: Boolean = false,
)

/**
 * One element of a captured UI tree. Immutable; producers (parsers, the Windows helper client) build trees and then call
 * [ElementPaths.assign] so every node carries its structural [path].
 *
 * Blank attributes are `null` (producers normalise `""` to `null`), so "has a text" is `text != null`.
 *
 * @property name Windows UI Automation `Name`; always null on Android.
 * @property identifier Android resource-id **or** Windows AutomationId (the UI labels it per platform).
 * @property properties Platform-specific extras (Android `package`, `index`; Windows ControlType, ClassName, patterns…).
 */
data class ElementNode(
    val path: ElementPath,
    val name: String? = null,
    val className: String,
    val identifier: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: Bounds,
    val flags: ElementFlags = ElementFlags(),
    val properties: Map<String, String> = emptyMap(),
    val children: List<ElementNode> = emptyList(),
)

object ElementPaths {
    /**
     * Returns [roots] with every node's path set from tree structure alone: root *i* is `"i"`, its child *j* is `"i/j"`, and so on.
     * Whatever paths the input nodes carried are replaced.
     */
    fun assign(roots: List<ElementNode>): List<ElementNode> =
        roots.mapIndexed { index, root -> assignFrom(root, ElementPath.root(index)) }

    private fun assignFrom(node: ElementNode, path: ElementPath): ElementNode =
        node.copy(
            path = path,
            children = node.children.mapIndexed { index, child -> assignFrom(child, path.child(index)) },
        )
}
