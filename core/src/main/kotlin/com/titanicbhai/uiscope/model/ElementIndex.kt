package com.titanicbhai.uiscope.model

/** The attributes the index counts occurrences of; used to decide whether a selector is unique. */
enum class Attribute { IDENTIFIER, NAME, TEXT, DESCRIPTION, CLASS }

/**
 * How to find "the same element" in a later capture of the same screen (plan §4.5):
 * first by [path] + [className] + [identifier]; failing that, by [identifier] + [identifierOccurrence].
 *
 * @property identifierOccurrence Zero-based position of the element among all nodes sharing its identifier, in paint order.
 */
data class ElementIdentity(
    val path: ElementPath,
    val className: String,
    val identifier: String?,
    val identifierOccurrence: Int,
)

/**
 * Read-only lookup structure over one [Capture], built once per capture (plan §3.10).
 *
 * - [nodes]: every node in **paint order**: parents before children, siblings in order, so a later entry paints on top of an
 *   earlier one. Iterating it in reverse visits later siblings first and the deepest node before its ancestors.
 * - Blank attributes are `null` on the nodes and are not counted.
 *
 * @throws IllegalArgumentException if two nodes of the capture share a path (a producer forgot [ElementPaths.assign]).
 */
class ElementIndex(val capture: Capture) {
    val nodes: List<ElementNode>
    val byPath: Map<ElementPath, ElementNode>

    private val parents = HashMap<ElementPath, ElementNode>()
    private val groups = HashMap<Attribute, HashMap<String, MutableList<ElementNode>>>()
    private val identifierOccurrence = HashMap<ElementPath, Int>()

    init {
        val flat = ArrayList<ElementNode>()
        val paths = HashMap<ElementPath, ElementNode>()
        // Iterative pre-order: a very deep tree must not overflow the stack.
        val pending = ArrayDeque<ElementNode>()
        capture.roots.asReversed().forEach { pending.addLast(it) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            require(paths.put(node.path, node) == null) { "Duplicate element path ${node.path} in capture ${capture.id}" }
            flat += node
            record(node)
            node.children.forEach { parents[it.path] = node }
            node.children.asReversed().forEach { pending.addLast(it) }
        }
        nodes = flat
        byPath = paths
    }

    private fun record(node: ElementNode) {
        add(Attribute.IDENTIFIER, node.identifier, node)?.let { identifierOccurrence[node.path] = it }
        add(Attribute.NAME, node.name, node)
        add(Attribute.TEXT, node.text, node)
        add(Attribute.DESCRIPTION, node.contentDescription, node)
        add(Attribute.CLASS, node.className, node)
    }

    /** Adds [node] to the group for [value] and returns its zero-based position in that group (null if [value] is blank). */
    private fun add(attribute: Attribute, value: String?, node: ElementNode): Int? {
        if (value.isNullOrEmpty()) return null
        val group = groups.getOrPut(attribute) { HashMap() }.getOrPut(value) { ArrayList() }
        group += node
        return group.size - 1
    }

    val size: Int get() = nodes.size

    fun parentOf(node: ElementNode): ElementNode? = parents[node.path]

    fun parentOf(path: ElementPath): ElementNode? = parents[path]

    /** The chain from the root down to the node's parent (root first); empty for a root. */
    fun ancestors(node: ElementNode): List<ElementNode> {
        val chain = ArrayList<ElementNode>()
        var current = parents[node.path]
        while (current != null) {
            chain += current
            current = parents[current.path]
        }
        return chain.asReversed()
    }

    /** All nodes whose [attribute] equals [value], in paint order. */
    fun nodesWith(attribute: Attribute, value: String): List<ElementNode> = groups[attribute]?.get(value) ?: emptyList()

    /** How many nodes have exactly this [value] for [attribute]. 1 means unique. */
    fun count(attribute: Attribute, value: String): Int = groups[attribute]?.get(value)?.size ?: 0

    /** True when the node has an identifier that at least one other node also has (the "duplicate id" badge). */
    fun hasDuplicateIdentifier(node: ElementNode): Boolean = node.identifier?.let { count(Attribute.IDENTIFIER, it) > 1 } ?: false

    fun identityOf(node: ElementNode): ElementIdentity =
        ElementIdentity(node.path, node.className, node.identifier, identifierOccurrence[node.path] ?: 0)

    /**
     * Finds the element [identity] (taken from an earlier capture) in this capture, or null if it is gone.
     * 1. Same path, same class and same identifier.
     * 2. Otherwise, if the identity has an identifier: the node with that identifier at the same occurrence position and the same class.
     */
    fun relocate(identity: ElementIdentity): ElementNode? {
        byPath[identity.path]?.let { candidate ->
            if (candidate.className == identity.className && candidate.identifier == identity.identifier) return candidate
        }
        val identifier = identity.identifier ?: return null
        val candidate = nodesWith(Attribute.IDENTIFIER, identifier).getOrNull(identity.identifierOccurrence) ?: return null
        return candidate.takeIf { it.className == identity.className }
    }
}
