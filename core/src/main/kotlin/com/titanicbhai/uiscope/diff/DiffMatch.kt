package com.titanicbhai.uiscope.diff

import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementNode

/** Which rule matched a pair of nodes (plan P6-1, in order). */
enum class MatchPass { PATH, IDENTIFIER, CONTENT_AND_PARENT, BOUNDS }

/** One differing field of a matched pair; `null` = the value is absent on that side (or was null). */
data class FieldChange(val field: String, val before: String?, val after: String?)

/** A matched pair: the same element in both captures ([pass] says which rule found it), with every differing field. */
data class MatchedPair(val before: ElementNode, val after: ElementNode, val pass: MatchPass, val changes: List<FieldChange>)

/** The full comparison of two captures: matched pairs plus whatever only exists on one side. */
data class DiffResult(val pairs: List<MatchedPair>, val added: List<ElementNode>, val removed: List<ElementNode>)

/**
 * RED STUB (plan P6-1, regression B5): matches nothing and reports every after-node as added and every before-node as removed —
 * exactly the "sees 0 nodes" behaviour of the old, deleted Diff code. The G12 test ([DiffMatchTest]) fails against this on its
 * assertions; the real engine replaces the body of [diff].
 */
object DiffMatch {
    fun diff(before: Capture, after: Capture): DiffResult =
        DiffResult(pairs = emptyList(), added = after.roots.flatMap(::flatten), removed = before.roots.flatMap(::flatten))

    private fun flatten(node: ElementNode): List<ElementNode> = listOf(node) + node.children.flatMap(::flatten)
}
