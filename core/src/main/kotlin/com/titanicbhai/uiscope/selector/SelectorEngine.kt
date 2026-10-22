package com.titanicbhai.uiscope.selector

import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.Target

/** How trustworthy a selector is (Appendix C). There is exactly one notion of fragility: [FRAGILE]. */
enum class Tier(val rank: Int, val label: String) {
    RELIABLE(0, "Reliable"),
    GOOD(1, "Good"),
    NEEDS_INDEX(2, "Needs index"),
    FRAGILE(3, "Fragile"),
}

/** Specific, never generic, warnings shown as chips next to a selector. */
enum class SelectorWarning(val message: String) {
    TEXT_LOCALIZED("depends on the app language"),
    TEXT_DYNAMIC("text looks dynamic (digits/time)"),
    IDS_OBFUSCATED_TREE("IDs in this app look obfuscated"),
    ID_NUMERIC_ONLY("AutomationId is numeric only (a Win32 control id)"),
}

/** The way a candidate identifies its element, numbered as in Appendix C's tables (the number is the tie-break order). */
enum class Strategy(val order: Int) {
    IDENTIFIER(1),
    DESCRIPTION(2),
    TEXT(3),
    CONTROL_TYPE_NAME(2),
    CLASS_NAME(3),
    IDENTIFIER_TEXT(4),
    CLASS_TEXT(5),
    CLASS_DESCRIPTION(5),
    IDENTIFIER_INDEX(6),
    ANCHOR_PATH(7),
    ABSOLUTE_PATH(8),
    COORDINATES(9),
}

/**
 * One way to select the target element.
 * @property matches How many elements [selector] selects in this capture, **computed by evaluating it** (never estimated).
 * @property expression The selector in neutral form, for display.
 * @property sharedMatches For an index candidate: how many elements its base attribute matches (the "N" of "matches N elements").
 */
data class Candidate(
    val strategy: Strategy,
    val selector: Selector,
    val expression: String,
    val matches: Int,
    val tier: Tier,
    val reasons: List<String>,
    val warnings: Set<SelectorWarning>,
    val sharedMatches: Int? = null,
) {
    /** Selects exactly the target and nothing else. */
    val isUnique: Boolean get() = matches == 1
}

/** The computed one-sentence summary plus the tier chip (§4.6). */
data class SelectorVerdict(
    val tier: Tier,
    val summary: String,
    /** Set for [Tier.NEEDS_INDEX]: how many elements share the attribute. */
    val sharedMatches: Int? = null,
)

data class SelectorReport(
    /** Best first; unique candidates before shared ones; coordinates always last. */
    val candidates: List<Candidate>,
    val best: Candidate,
    val verdict: SelectorVerdict,
    /** Tree-level warnings plus the best candidate's own warnings. */
    val warnings: Set<SelectorWarning>,
)

/** Generates and ranks selectors for an element, deterministically (Appendix C). */
object SelectorEngine {
    fun suggest(index: ElementIndex, node: ElementNode): SelectorReport {
        val windows = index.capture.target is Target.Windows
        val context = Context(index, node, windows, TextHeuristics.idsLookObfuscated(index))
        val raw = if (windows) windowsCandidates(context) else androidCandidates(context)
        val candidates = raw.distinctBy { it.second }.mapNotNull { (strategy, selector) -> evaluate(context, strategy, selector) }
        val sorted = candidates.sortedWith(RANKING)
        // An absolute path always selects exactly its own node, so a unique, non-coordinate candidate always exists.
        val best = sorted.firstOrNull { it.isUnique && it.strategy != Strategy.COORDINATES }
            ?: error("No unique selector for ${node.path}: the capture is inconsistent")
        val warnings = LinkedHashSet<SelectorWarning>()
        if (context.obfuscated) warnings += SelectorWarning.IDS_OBFUSCATED_TREE
        warnings += best.warnings
        return SelectorReport(sorted, best, verdictOf(context, best), warnings)
    }

    private class Context(val index: ElementIndex, val node: ElementNode, val windows: Boolean, val obfuscated: Boolean)

    // ── candidate generation (Appendix C) ──────────────────────────────────────────────────────

    private fun androidCandidates(c: Context): List<Pair<Strategy, Selector>> {
        val n = c.node
        val out = ArrayList<Pair<Strategy, Selector>>()
        val id = n.identifier
        val text = n.text
        val desc = n.contentDescription
        if (id != null) out += Strategy.IDENTIFIER to Selector.Attr(SelectorKey.IDENTIFIER, id)
        if (desc != null) out += Strategy.DESCRIPTION to Selector.Attr(SelectorKey.DESCRIPTION, desc)
        if (text != null) out += Strategy.TEXT to Selector.Attr(SelectorKey.TEXT, text)
        if (id != null && text != null) out += Strategy.IDENTIFIER_TEXT to Selector.Attr(Condition(SelectorKey.IDENTIFIER, id), Condition(SelectorKey.TEXT, text))
        if (text != null) out += Strategy.CLASS_TEXT to Selector.Attr(Condition(SelectorKey.CLASS, n.className), Condition(SelectorKey.TEXT, text))
        if (desc != null) out += Strategy.CLASS_DESCRIPTION to Selector.Attr(Condition(SelectorKey.CLASS, n.className), Condition(SelectorKey.DESCRIPTION, desc))
        indexCandidate(c, id?.let { Selector.Attr(SelectorKey.IDENTIFIER, it) })?.let { out += Strategy.IDENTIFIER_INDEX to it }
        anchorCandidate(c)?.let { out += Strategy.ANCHOR_PATH to it }
        out += Strategy.ABSOLUTE_PATH to Selector.Path(n.path)
        coordinates(n)?.let { out += Strategy.COORDINATES to it }
        return out
    }

    private fun windowsCandidates(c: Context): List<Pair<Strategy, Selector>> {
        val n = c.node
        val out = ArrayList<Pair<Strategy, Selector>>()
        val id = n.identifier
        val name = n.name
        val type = n.properties["ControlType"]
        if (id != null) out += Strategy.IDENTIFIER to Selector.Attr(SelectorKey.IDENTIFIER, id)
        if (type != null && name != null) out += Strategy.CONTROL_TYPE_NAME to Selector.Attr(Condition(SelectorKey.CONTROL_TYPE, type), Condition(SelectorKey.NAME, name))
        if (name != null) out += Strategy.CLASS_NAME to Selector.Attr(Condition(SelectorKey.CLASS, n.className), Condition(SelectorKey.NAME, name))
        indexCandidate(c, id?.let { Selector.Attr(SelectorKey.IDENTIFIER, it) })?.let { out += Strategy.IDENTIFIER_INDEX to it }
        anchorCandidate(c)?.let { out += Strategy.ANCHOR_PATH to it }
        out += Strategy.ABSOLUTE_PATH to Selector.Path(n.path)
        coordinates(n)?.let { out += Strategy.COORDINATES to it }
        return out
    }

    /** "attribute + occurrence index", offered only when the attribute is shared (for a unique one the plain candidate is enough). */
    private fun indexCandidate(c: Context, base: Selector.Attr?): Selector? {
        if (base == null) return null
        val shared = SelectorEvaluator.evaluate(c.index, base)
        if (shared.size < 2) return null
        val position = shared.indexOfFirst { it.path == c.node.path }
        return if (position >= 0) Selector.Index(base, position) else null
    }

    /** Nearest ancestor with a unique identifier (Windows: or a unique ControlType + Name) plus the child positions down to the node. */
    private fun anchorCandidate(c: Context): Selector? {
        for (ancestor in c.index.ancestors(c.node).asReversed()) {
            val anchor = uniqueAnchor(c, ancestor) ?: continue
            val relative = c.node.path.value.split('/').drop(ancestor.path.depth + 1).map { it.toInt() }
            return if (relative.isEmpty()) null else Selector.Anchor(anchor, relative)
        }
        return null
    }

    private fun uniqueAnchor(c: Context, ancestor: ElementNode): Selector.Attr? {
        ancestor.identifier?.let { id ->
            val attr = Selector.Attr(SelectorKey.IDENTIFIER, id)
            if (SelectorEvaluator.count(c.index, attr) == 1) return attr
        }
        if (c.windows) {
            val type = ancestor.properties["ControlType"]
            val name = ancestor.name
            if (type != null && name != null) {
                val attr = Selector.Attr(Condition(SelectorKey.CONTROL_TYPE, type), Condition(SelectorKey.NAME, name))
                if (SelectorEvaluator.count(c.index, attr) == 1) return attr
            }
        }
        return null
    }

    private fun coordinates(node: ElementNode): Selector? =
        if (node.bounds.isEmpty) null else Selector.Coordinates(node.bounds.x + node.bounds.width / 2, node.bounds.y + node.bounds.height / 2)

    // ── evaluation and classification ──────────────────────────────────────────────────────────

    private fun evaluate(c: Context, strategy: Strategy, selector: Selector): Candidate? {
        val result = SelectorEvaluator.evaluate(c.index, selector)
        if (result.none { it.path == c.node.path }) return null // does not even select the target (for example covered by an overlay)
        val warnings = LinkedHashSet<SelectorWarning>()
        val textBased = conditionsOf(selector).filter { it.key == SelectorKey.TEXT || it.key == SelectorKey.NAME }
        if (textBased.isNotEmpty()) warnings += SelectorWarning.TEXT_LOCALIZED
        val dynamic = textBased.any { TextHeuristics.looksDynamic(it.value) }
        if (dynamic) warnings += SelectorWarning.TEXT_DYNAMIC
        val numericId = c.windows && conditionsOf(selector).any { it.key == SelectorKey.IDENTIFIER && TextHeuristics.isNumericOnly(it.value) }
        if (numericId) warnings += SelectorWarning.ID_NUMERIC_ONLY
        val shared = (selector as? Selector.Index)?.let { SelectorEvaluator.count(c.index, it.base) }
        val (tier, reasons) = classify(c, selector, result.size, dynamic, numericId, shared)
        return Candidate(strategy, selector, selector.neutral(), result.size, tier, reasons, warnings, shared)
    }

    private fun conditionsOf(selector: Selector): List<Condition> = when (selector) {
        is Selector.Attr -> selector.conditions
        is Selector.Index -> selector.base.conditions
        is Selector.Anchor -> emptyList() // the anchor locates, it is not what identifies the target
        is Selector.Path, is Selector.Coordinates -> emptyList()
    }

    private fun classify(c: Context, selector: Selector, matches: Int, dynamic: Boolean, numericId: Boolean, shared: Int?): Pair<Tier, List<String>> =
        when (selector) {
            is Selector.Path -> Tier.FRAGILE to listOf("Position in the tree only")
            is Selector.Anchor -> Tier.FRAGILE to listOf("Path from a unique anchor element")
            is Selector.Coordinates -> Tier.FRAGILE to listOf("Screen coordinates: last resort")
            is Selector.Index -> Tier.NEEDS_INDEX to listOf("Attribute shared by $shared elements; index ${selector.index} breaks if the list changes")
            is Selector.Attr -> classifyAttr(c, selector, matches, dynamic, numericId)
        }

    private fun classifyAttr(c: Context, attr: Selector.Attr, matches: Int, dynamic: Boolean, numericId: Boolean): Pair<Tier, List<String>> {
        val keys = attr.conditions.map { it.key }.toSet()
        if (matches > 1) return Tier.NEEDS_INDEX to listOf("Shared by $matches elements")
        val textBased = SelectorKey.TEXT in keys || SelectorKey.NAME in keys
        return when {
            keys == setOf(SelectorKey.IDENTIFIER) -> when {
                numericId -> Tier.GOOD to listOf("Unique identifier, but numeric-only")
                c.obfuscated -> Tier.GOOD to listOf("Unique identifier, but identifiers in this app look obfuscated")
                else -> Tier.RELIABLE to listOf("Unique identifier")
            }
            textBased && dynamic -> Tier.FRAGILE to listOf("Unique, but the text looks dynamic")
            textBased -> Tier.GOOD to listOf("Unique text/name")
            SelectorKey.DESCRIPTION in keys -> Tier.GOOD to listOf("Unique content description")
            else -> Tier.FRAGILE to listOf("Class only: no stable attribute")
        }
    }

    // ── ranking: Reliable > Good(content-desc) > Good(text) > Needs index > Fragile ─────────────

    private val RANKING: Comparator<Candidate> = compareBy<Candidate>(
        { it.strategy == Strategy.COORDINATES },           // coordinates are always last
        { !it.isUnique },                                  // a candidate that selects exactly the target comes first
        { it.tier.rank },
        { goodSubRank(it) },
        { fragileSubRank(it) },
        // Within a tier Appendix C says "prefer the shorter expression". Neutral names make length depend on spelling
        // (`class` is shorter than `controlType`), so the table order of Appendix C decides first and length second.
        { it.strategy.order },
        { it.expression.length },
    )

    private fun goodSubRank(c: Candidate): Int =
        if (c.tier == Tier.GOOD && c.selector is Selector.Attr && c.selector.conditions.any { it.key == SelectorKey.DESCRIPTION }) 0 else 1

    /** Among fragile candidates, path-based ones (anchor, absolute path) are preferred over dynamic-text or class-only ones. */
    private fun fragileSubRank(c: Candidate): Int =
        if (c.tier != Tier.FRAGILE) 0 else if (c.selector is Selector.Anchor || c.selector is Selector.Path) c.strategy.order else 100

    // ── the verdict sentence (§4.6) ─────────────────────────────────────────────────────────────

    private fun verdictOf(c: Context, best: Candidate): SelectorVerdict {
        val summary = when (val selector = best.selector) {
            is Selector.Index -> {
                val base = selector.base.conditions.single()
                "`${shown(c, base)}` matches ${best.sharedMatches} elements; index ${selector.index} breaks if the list changes"
            }
            is Selector.Attr -> attrSummary(c, selector, best)
            else -> "No stable attribute; path-based selector only"
        }
        return SelectorVerdict(best.tier, summary, best.sharedMatches)
    }

    private fun attrSummary(c: Context, attr: Selector.Attr, best: Candidate): String {
        val what = attr.conditions.joinToString(" + ") { label(c, it) }
        val value = attr.conditions.joinToString(" + ") { shown(c, it) }
        val base = if (best.tier == Tier.RELIABLE) "Unique $what `$value` (1 match)" else "Unique $what '${value}' (1 match)"
        return when {
            best.tier == Tier.FRAGILE && SelectorWarning.TEXT_DYNAMIC in best.warnings -> "$base — text looks dynamic and may change between runs"
            SelectorWarning.ID_NUMERIC_ONLY in best.warnings -> "$base — numeric ids are Win32 control ids"
            c.obfuscated && attr.conditions.any { it.key == SelectorKey.IDENTIFIER } && best.tier == Tier.GOOD -> "$base — ids in this app look obfuscated"
            SelectorWarning.TEXT_LOCALIZED in best.warnings -> "$base — breaks if the app language changes"
            else -> base
        }
    }

    private fun label(c: Context, condition: Condition): String = when (condition.key) {
        SelectorKey.IDENTIFIER -> if (c.windows) "AutomationId" else "resource-id"
        SelectorKey.TEXT -> "text"
        SelectorKey.DESCRIPTION -> "content-desc"
        SelectorKey.NAME -> "Name"
        SelectorKey.CONTROL_TYPE -> "ControlType"
        SelectorKey.CLASS -> if (c.windows) "ClassName" else "class"
    }

    /** Android resource ids are shown without their `package:id/` prefix, as in the examples of §4.6. */
    private fun shown(c: Context, condition: Condition): String =
        if (condition.key == SelectorKey.IDENTIFIER && !c.windows) condition.value.substringAfterLast('/') else condition.value
}
