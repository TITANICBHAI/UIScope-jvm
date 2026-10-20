package com.titanicbhai.uiscope.selector

import com.titanicbhai.uiscope.model.ElementPath

/** The element attributes a selector can test. Codegen renders them per platform (IDENTIFIER = resource-id or AutomationId, …). */
enum class SelectorKey(val neutralName: String) {
    IDENTIFIER("identifier"),
    TEXT("text"),
    DESCRIPTION("description"),
    NAME("name"),
    CONTROL_TYPE("controlType"),
    CLASS("class"),
}

/** `key == value`, exact and case-sensitive. */
data class Condition(val key: SelectorKey, val value: String)

/**
 * A selector in neutral form (Appendix C): independent of any test framework. [SelectorEvaluator] runs it against a capture;
 * `codegen` renders it for a target language.
 */
sealed interface Selector {
    /** Every element for which all [conditions] hold. */
    data class Attr(val conditions: List<Condition>) : Selector {
        init {
            require(conditions.isNotEmpty()) { "An attribute selector needs at least one condition" }
        }

        constructor(key: SelectorKey, value: String) : this(listOf(Condition(key, value)))

        constructor(single: Condition) : this(listOf(single))

        constructor(first: Condition, second: Condition) : this(listOf(first, second))
    }

    /** The [index]-th (zero-based, in document order) element matched by [base]. */
    data class Index(val base: Attr, val index: Int) : Selector {
        init {
            require(index >= 0) { "Index must not be negative" }
        }
    }

    /** From the element(s) matched by [anchor], follow child positions [relativePath] (zero-based). */
    data class Anchor(val anchor: Attr, val relativePath: List<Int>) : Selector {
        init {
            require(relativePath.isNotEmpty()) { "An anchored selector needs a relative path (otherwise the anchor itself is the target)" }
        }
    }

    /** Absolute position: child indexes from the first root (the window / screen root). */
    data class Path(val path: ElementPath) : Selector

    /** A point in capture space (last resort). */
    data class Coordinates(val x: Int, val y: Int) : Selector
}

/** Neutral, human-readable text of a selector, such as `Index(Attr(identifier="a:id/row"), 3)`. Stable, used for ranking and display. */
fun Selector.neutral(): String = when (this) {
    is Selector.Attr -> "Attr(" + conditions.joinToString(", ") { "${it.key.neutralName}=${quote(it.value)}" } + ")"
    is Selector.Index -> "Index(${base.neutral()}, $index)"
    is Selector.Anchor -> "Anchor(${anchor.neutral()}, ${relativePath.joinToString("/")})"
    is Selector.Path -> "Path(${path.value})"
    is Selector.Coordinates -> "Point($x,$y)"
}

private fun quote(value: String): String {
    val out = StringBuilder("\"")
    for (c in value) {
        when {
            c == '\\' -> out.append("\\\\")
            c == '"' -> out.append("\\\"")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' || c == '\u007f' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> out.append(c)
        }
    }
    return out.append('"').toString()
}
