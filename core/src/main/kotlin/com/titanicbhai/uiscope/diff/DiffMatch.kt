package com.titanicbhai.uiscope.diff

import com.titanicbhai.uiscope.model.Attribute
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementFlags
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath

/** Which rule matched a pair of nodes (plan P6-1, in order). */
enum class MatchPass { PATH, IDENTIFIER, CONTENT_AND_PARENT, BOUNDS }

/** One differing field of a matched pair; `null` = the value is absent on that side (or was null). */
data class FieldChange(val field: String, val before: String?, val after: String?)

/** A matched pair: the same element in both captures ([pass] says which rule found it), with every differing field. */
data class MatchedPair(val before: ElementNode, val after: ElementNode, val pass: MatchPass, val changes: List<FieldChange>)

/** The full comparison of two captures: matched pairs plus whatever only exists on one side. */
data class DiffResult(val pairs: List<MatchedPair>, val added: List<ElementNode>, val removed: List<ElementNode>)

/**
 * The Compare matching engine (plan P6-1, regression B5). Pairs every element of the earlier capture with the same element of
 * the later one in four passes, most specific first:
 *
 * 1. [MatchPass.PATH] — same structural path, class and identifier (the identifier may be null on both sides), the §4.5 identity.
 * 2. [MatchPass.IDENTIFIER] — same identifier, class and occurrence index among the nodes sharing that identifier: a node that
 *    moved within the tree keeps its identity as long as its position among its id-siblings is unchanged.
 * 3. [MatchPass.CONTENT_AND_PARENT] — same class and text (or, for textless nodes, content description) with the parent already
 *    a matched pair; nodes are visited in paint order, so parents pair before their children.
 * 4. [MatchPass.BOUNDS] — same class and a bounds overlap (IoU) of at least 0.7, closest overlap first.
 *
 * Whatever stays unpaired is [DiffResult.added] / [DiffResult.removed]. For every pair the engine diffs the content fields
 * (name, text, description, every flag, bounds, properties) in that fixed order; class and path never differ inside a pair by
 * construction. Nodes without identifiers participate like any other node — the deleted 1.0 code ignored them (B5).
 *
 * Windows captures are supported like Android ones: only the platform-neutral node fields are read.
 */
object DiffMatch {

    fun diff(before: Capture, after: Capture): DiffResult {
        val a = ElementIndex(before)
        val b = ElementIndex(after)
        val matchOfA = HashMap<ElementPath, ElementNode>()
        val usedB = HashSet<ElementPath>()
        val bByClass = b.nodes.groupBy { it.className }
        val pairs = ArrayList<MatchedPair>()

        fun pair(aNode: ElementNode, bNode: ElementNode, pass: MatchPass) {
            matchOfA[aNode.path] = bNode
            usedB += bNode.path
            pairs += MatchedPair(aNode, bNode, pass, changes(aNode, bNode))
        }

        fun parentMatched(aNode: ElementNode, bCandidate: ElementNode): Boolean {
            val parentA = a.parentOf(aNode) ?: return b.parentOf(bCandidate) == null
            val parentB = matchOfA[parentA.path] ?: return false
            return parentB.path == b.parentOf(bCandidate)?.path
        }

        // Pass 1 — path + class + identifier (identifier may be null on both sides).
        for (node in a.nodes) {
            val candidate = b.byPath[node.path] ?: continue
            if (candidate.className == node.className && candidate.identifier == node.identifier) pair(node, candidate, MatchPass.PATH)
        }
        // Pass 2 — identifier + class + occurrence index among the nodes sharing the identifier.
        for (node in a.nodes) {
            if (matchOfA.containsKey(node.path)) continue
            val identifier = node.identifier ?: continue
            val occurrence = a.identityOf(node).identifierOccurrence
            val candidate = b.nodesWith(Attribute.IDENTIFIER, identifier).getOrNull(occurrence) ?: continue
            if (candidate.path in usedB) continue
            if (candidate.className == node.className) pair(node, candidate, MatchPass.IDENTIFIER)
        }
        // Pass 3 — class + text (or description) + matched parent, in paint order so parents pair first.
        for (node in a.nodes) {
            if (matchOfA.containsKey(node.path)) continue
            val group = if (node.text != null) b.nodesWith(Attribute.TEXT, node.text) else b.nodesWith(Attribute.DESCRIPTION, node.contentDescription ?: continue)
            for (candidate in group) {
                if (candidate.path in usedB || candidate.className != node.className) continue
                if (!parentMatched(node, candidate)) continue
                pair(node, candidate, MatchPass.CONTENT_AND_PARENT)
                break
            }
        }
        // Pass 4 — same class and bounds IoU >= 0.7, closest overlap first (ties go to the first in paint order).
        for (node in a.nodes) {
            if (matchOfA.containsKey(node.path)) continue
            var best: ElementNode? = null
            var bestIou = 0.0
            for (candidate in bByClass[node.className].orEmpty()) {
                if (candidate.path in usedB) continue
                val iou = node.bounds.intersectionOverUnion(candidate.bounds)
                if (iou > bestIou) {
                    bestIou = iou
                    best = candidate
                }
            }
            if (best != null && bestIou >= IOU_THRESHOLD) pair(node, best, MatchPass.BOUNDS)
        }

        val added = b.nodes.filter { it.path !in usedB }
        val removed = a.nodes.filter { it.path !in matchOfA.keys }
        return DiffResult(pairs, added, removed)
    }

    /** Field diffs in the fixed order the UI and the tests rely on: name, text, description, flags, bounds, properties. */
    private fun changes(before: ElementNode, after: ElementNode): List<FieldChange> {
        val out = ArrayList<FieldChange>()
        if (before.name != after.name) out += FieldChange("name", before.name, after.name)
        if (before.text != after.text) out += FieldChange("text", before.text, after.text)
        if (before.contentDescription != after.contentDescription) out += FieldChange("description", before.contentDescription, after.contentDescription)
        flagChanges(before.flags, after.flags, out)
        if (before.bounds != after.bounds) out += FieldChange("bounds", render(before.bounds), render(after.bounds))
        for (key in (before.properties.keys + after.properties.keys).sorted()) {
            val was = before.properties[key]
            val now = after.properties[key]
            if (was != now) out += FieldChange("properties.$key", was, now)
        }
        return out
    }

    private fun flagChanges(before: ElementFlags, after: ElementFlags, out: MutableList<FieldChange>) {
        if (before.enabled != after.enabled) out += FieldChange("flags.enabled", before.enabled.toString(), after.enabled.toString())
        if (before.clickable != after.clickable) out += FieldChange("flags.clickable", before.clickable.toString(), after.clickable.toString())
        if (before.longClickable != after.longClickable) out += FieldChange("flags.longClickable", before.longClickable.toString(), after.longClickable.toString())
        if (before.checkable != after.checkable) out += FieldChange("flags.checkable", before.checkable.toString(), after.checkable.toString())
        if (before.checked != after.checked) out += FieldChange("flags.checked", before.checked.toString(), after.checked.toString())
        if (before.focusable != after.focusable) out += FieldChange("flags.focusable", before.focusable.toString(), after.focusable.toString())
        if (before.focused != after.focused) out += FieldChange("flags.focused", before.focused.toString(), after.focused.toString())
        if (before.scrollable != after.scrollable) out += FieldChange("flags.scrollable", before.scrollable.toString(), after.scrollable.toString())
        if (before.selected != after.selected) out += FieldChange("flags.selected", before.selected.toString(), after.selected.toString())
        if (before.offscreen != after.offscreen) out += FieldChange("flags.offscreen", before.offscreen.toString(), after.offscreen.toString())
        if (before.password != after.password) out += FieldChange("flags.password", before.password.toString(), after.password.toString())
    }

    private fun render(bounds: Bounds) = "${bounds.x},${bounds.y} ${bounds.width}x${bounds.height}"

    private const val IOU_THRESHOLD = 0.7
}
