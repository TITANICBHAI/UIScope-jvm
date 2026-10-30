package com.titanicbhai.uiscope.json

import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome

/**
 * A small, strict JSON reader for files UIScope itself writes (settings). Values come back as `Map<String, Any?>`, `List<Any?>`,
 * `String`, `Long`, `Double`, `Boolean` or `null`. It rejects trailing text, control characters inside strings and nesting deeper than
 * [MAX_DEPTH]; errors say where (the offset) and never repeat file content.
 *
 * It deliberately has no dependencies (Round 5 decision: one JSON stack for settings, captures and bundles, fully testable without
 * Gradle; see docs/PROGRESS.md). Callers that read deeper documents (captures) pass a larger `maxDepth`.
 */
object JsonReader {
    const val MAX_DEPTH = 64

    fun parse(text: String, maxDepth: Int = MAX_DEPTH): Outcome<Any?> =
        try {
            Outcome.Success(Parser(text.removePrefix("\uFEFF"), maxDepth).parseDocument())
        } catch (e: JsonSyntaxException) {
            Outcome.Failure(CodedError(ErrorCodes.PARSE_FAILED, "Not valid JSON (offset ${e.offset}: ${e.reason})"))
        }

    private class JsonSyntaxException(val offset: Int, val reason: String) : Exception("$reason at $offset")

    private class Parser(private val s: String, private val maxDepth: Int) {
        private var i = 0
        private val number = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")

        fun parseDocument(): Any? {
            skipWhitespace()
            val value = value(0)
            skipWhitespace()
            if (i != s.length) fail("unexpected text after the value")
            return value
        }

        private fun fail(reason: String): Nothing = throw JsonSyntaxException(i, reason)

        private fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun value(depth: Int): Any? {
            if (depth > maxDepth) fail("nested too deeply")
            if (i >= s.length) fail("unexpected end")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> num()
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, i)) fail("unknown value")
            i += word.length
            return result
        }

        private fun num(): Any {
            val m = number.matchAt(s, i) ?: fail("not a value")
            i += m.value.length
            val integral = m.groups[2] == null && m.groups[3] == null
            return if (integral) m.value.toLongOrNull() ?: m.value.toDouble() else m.value.toDouble()
        }

        private fun str(): String {
            i++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c < ' ' -> fail("control character in a string")
                    c == '\\' -> out.append(escape())
                    else -> out.append(c)
                }
            }
        }

        private fun escape(): Char {
            if (i >= s.length) fail("unterminated escape")
            return when (val e = s[i++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (i + 4 > s.length) fail("short unicode escape")
                    val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad unicode escape")
                    i += 4
                    code.toChar()
                }
                else -> fail("unknown escape \\$e")
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                skipWhitespace()
                out += value(depth + 1)
                skipWhitespace()
                if (i >= s.length) fail("unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> fail("expected , or ]")
                }
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipWhitespace()
                if (i >= s.length || s[i] != '"') fail("expected a key")
                val key = str()
                skipWhitespace()
                if (i >= s.length || s[i++] != ':') fail("expected :")
                skipWhitespace()
                out[key] = value(depth + 1)
                skipWhitespace()
                if (i >= s.length) fail("unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("expected , or }")
                }
            }
        }
    }
}

/** JSON text output: string literals that are valid in any encoding (everything outside printable ASCII is `\uXXXX`). */
object JsonWriter {
    fun string(value: String): String = buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' || c > '~' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }
}
