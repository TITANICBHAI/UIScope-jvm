package com.titanicbhai.uiscope.codegen

import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.selector.Condition
import com.titanicbhai.uiscope.selector.Selector
import com.titanicbhai.uiscope.selector.SelectorKey

/**
 * XPath 1.0 for Android selectors. The expressions work on `uiautomator dump` files (a hierarchy element holding node elements) and on
 * Appium page source alike because they never name an element tag: they use the wildcard step with an attribute predicate, and
 * positions as wildcard child steps.
 */
object XPath {
    private fun attribute(key: SelectorKey): String? = when (key) {
        SelectorKey.IDENTIFIER -> "resource-id"
        SelectorKey.TEXT -> "text"
        SelectorKey.DESCRIPTION -> "content-desc"
        SelectorKey.CLASS -> "class"
        SelectorKey.NAME, SelectorKey.CONTROL_TYPE -> null // Windows only
    }

    /** The expression for [selector], or null when XPath cannot express it (coordinates, Windows-only attributes). */
    fun render(selector: Selector): String? = when (selector) {
        is Selector.Attr -> predicate(selector.conditions)?.let { "//*[$it]" }
        is Selector.Index -> render(selector.base)?.let { "($it)[${selector.index + 1}]" }
        is Selector.Anchor -> render(selector.anchor)?.let { it + selector.relativePath.joinToString("") { step -> "/*[${step + 1}]" } }
        is Selector.Path -> "/hierarchy" + selector.path.value.split('/').joinToString("") { "/*[${it.toInt() + 1}]" }
        is Selector.Coordinates -> null
    }

    private fun predicate(conditions: List<Condition>): String? {
        val parts = conditions.map { c -> attribute(c.key)?.let { "@$it=${Escapers.xpath(c.value)}" } ?: return null }
        return parts.joinToString(" and ")
    }
}

/**
 * A small evaluator for exactly the XPath that [XPath.render] produces, so "matches N" for an XPath can be computed without a DOM.
 * Tests cross-check it against `javax.xml.xpath` on the exported XML.
 *
 * Grammar: an expression is a path, or a parenthesised path followed by a 1-based position. A path starts with the descendant
 * wildcard step (optionally with an attribute predicate) or with the hierarchy root, followed by wildcard child steps that each
 * carry a position. A predicate is one or more `@name=literal` tests joined by `and`; a literal is a quoted string or `concat()`
 * of literals.
 */
object XPathSubset {
    sealed interface Outcome {
        data class Nodes(val nodes: List<ElementNode>) : Outcome

        /** [expression] is outside the supported subset; [reason] says where it stopped making sense. */
        data class Unsupported(val reason: String) : Outcome
    }

    fun tryEvaluate(index: ElementIndex, expression: String): Outcome =
        try {
            Outcome.Nodes(Parser(expression, index).parse())
        } catch (e: IllegalArgumentException) {
            Outcome.Unsupported(e.message ?: "unsupported expression")
        }

    /** Nodes selected in document order, or null when [expression] is outside the supported subset. */
    fun evaluate(index: ElementIndex, expression: String): List<ElementNode>? = (tryEvaluate(index, expression) as? Outcome.Nodes)?.nodes

    private class Parser(private val s: String, private val index: ElementIndex) {
        private var pos = 0

        fun parse(): List<ElementNode> {
            val result = if (peek() == '(') {
                pos++
                val inner = path()
                expect(')')
                val position = bracketNumber()
                listOfNotNull(inner.getOrNull(position - 1))
            } else {
                path()
            }
            require(pos == s.length) { "Unexpected text at $pos" }
            return result
        }

        private fun path(): List<ElementNode> {
            var current: List<ElementNode>
            if (s.startsWith("//*", pos)) {
                pos += 3
                current = if (peek() == '[') matching(predicate()) else index.nodes
            } else {
                require(s.startsWith("/hierarchy", pos)) { "Unsupported start" }
                pos += "/hierarchy".length
                // the virtual hierarchy element: its children are the roots, the first step must pick among them
                require(s.startsWith("/*", pos)) { "A path needs at least one step" }
                pos += 2
                val first = if (peek() == '[') bracketNumber() else null
                current = listOfNotNull(if (first == null) null else index.capture.roots.getOrNull(first - 1))
            }
            while (s.startsWith("/*", pos)) {
                pos += 2
                val position = if (peek() == '[') bracketNumber() else null
                require(position != null) { "Child steps need a position" }
                current = current.mapNotNull { it.children.getOrNull(position - 1) }.distinctBy { it.path }
            }
            return current
        }

        private fun matching(conditions: List<Pair<String, String>>): List<ElementNode> =
            index.nodes.filter { node -> conditions.all { (name, value) -> attributeOf(node, name) == value } }

        private fun attributeOf(node: ElementNode, name: String): String = when (name) {
            "resource-id" -> node.identifier.orEmpty()
            "text" -> node.text.orEmpty()
            "content-desc" -> node.contentDescription.orEmpty()
            "class" -> node.className
            else -> throw IllegalArgumentException("Unsupported attribute $name")
        }

        private fun predicate(): List<Pair<String, String>> {
            expect('[')
            val out = ArrayList<Pair<String, String>>()
            while (true) {
                expect('@')
                val start = pos
                while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '-')) pos++
                val name = s.substring(start, pos)
                expect('=')
                out += name to literal()
                if (s.startsWith(" and ", pos)) pos += 5 else break
            }
            expect(']')
            return out
        }

        private fun literal(): String {
            if (s.startsWith("concat(", pos)) {
                pos += "concat(".length
                val sb = StringBuilder()
                while (true) {
                    sb.append(literal())
                    if (s.startsWith(", ", pos)) pos += 2 else break
                }
                expect(')')
                return sb.toString()
            }
            val quote = peek()
            require(quote == '\'' || quote == '"') { "Expected a string literal at $pos" }
            val end = s.indexOf(quote, pos + 1)
            require(end > 0) { "Unterminated literal" }
            val value = s.substring(pos + 1, end)
            pos = end + 1
            return value
        }

        private fun bracketNumber(): Int {
            expect('[')
            val start = pos
            while (pos < s.length && s[pos].isDigit()) pos++
            require(pos > start) { "Expected a number at $pos" }
            val n = s.substring(start, pos).toInt()
            expect(']')
            return n
        }

        private fun peek(): Char? = s.getOrNull(pos)

        private fun expect(c: Char) {
            require(peek() == c) { "Expected '$c' at $pos" }
            pos++
        }
    }
}
