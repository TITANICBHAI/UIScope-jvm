package com.titanicbhai.uiscope.codegen

import com.titanicbhai.uiscope.export.XmlExporter

/**
 * String-literal escaping per target language (fixes B6). Every function returns a complete, quoted literal (or, where the language
 * has no escape for a character, a parenthesised concatenation) that evaluates to exactly the original text.
 */
object Escapers {
    private fun isControl(c: Char) = c < ' ' || c == '\u007f'

    private fun hex4(c: Char) = c.code.toString(16).padStart(4, '0')

    /** Python, single-quoted: `\`, `'`, newline, CR, tab; other control characters as `\xNN`; lone surrogates as `\uXXXX`. */
    fun python(text: String): String = buildString {
        append('\'')
        for ((i, c) in text.withIndex()) {
            when {
                c == '\\' -> append("\\\\")
                c == '\'' -> append("\\'")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                isControl(c) -> append("\\x").append(c.code.toString(16).padStart(2, '0'))
                loneSurrogate(text, i) -> append("\\u").append(hex4(c))
                else -> append(c)
            }
        }
        append('\'')
    }

    /** Kotlin, double-quoted: `\`, `"`, `$` (string templates), newline/CR/tab, other control characters and lone surrogates as `\uXXXX`. */
    fun kotlin(text: String): String = buildString {
        append('"')
        for ((i, c) in text.withIndex()) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '$' -> append("\\$")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                isControl(c) || loneSurrogate(text, i) -> append("\\u").append(hex4(c))
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * Java, double-quoted. Java translates `\uXXXX` **before** lexing, so `\u000a` inside a literal would break the line: control
     * characters therefore use octal escapes. `\\` followed by `u` is safe because the second backslash is not eligible to start a
     * Unicode escape.
     */
    fun java(text: String): String = buildString {
        append('"')
        for ((i, c) in text.withIndex()) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\b' -> append("\\b")
                c == '\u000c' -> append("\\f")
                isControl(c) -> append('\\').append(c.code.toString(8).padStart(3, '0'))
                loneSurrogate(text, i) -> append("\\u").append(hex4(c))
                else -> append(c)
            }
        }
        append('"')
    }

    /** C#, regular double-quoted string. */
    fun csharp(text: String): String = buildString {
        append('"')
        for ((i, c) in text.withIndex()) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\u0000' -> append("\\0")
                isControl(c) || loneSurrogate(text, i) -> append("\\u").append(hex4(c))
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * PowerShell, single-quoted (no expansion). The single quote and its Unicode look-alikes (U+2018, U+2019, U+201A, U+201B, which
     * PowerShell also accepts as quote characters) are doubled. Control characters (and lone surrogates) have no escape in single
     * quotes, so the literal becomes a parenthesised concatenation with `[char]N`.
     */
    fun powershell(text: String): String {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) parts += "'$current'"
            current.setLength(0)
        }
        for ((i, c) in text.withIndex()) {
            when {
                c == '\'' || c == '\u2018' || c == '\u2019' || c == '\u201a' || c == '\u201b' -> current.append(c).append(c)
                isControl(c) || loneSurrogate(text, i) -> { // a lone surrogate cannot be written into a UTF-8 script file
                    flush()
                    parts += "[char]${c.code}"
                }
                else -> current.append(c)
            }
        }
        flush()
        return when {
            parts.isEmpty() -> "''"
            parts.size == 1 && !parts[0].startsWith("[") -> parts[0]
            else -> "(" + (if (parts[0].startsWith("[")) "'' + " else "") + parts.joinToString(" + ") + ")"
        }
    }

    /**
     * AutoHotkey v2, double-quoted: backtick is the escape character (`` ` `` → ``` `` ```, `"` → `` `" ``, `` `n ``, `` `r ``, `` `t ``).
     * Other control characters and lone surrogates use `Chr(N)` joined with the `.` operator.
     */
    fun autohotkey(text: String): String {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) parts += "\"$current\""
            current.setLength(0)
        }
        for ((i, c) in text.withIndex()) {
            when {
                c == '`' -> current.append("``")
                c == '"' -> current.append("`\"")
                c == '\n' -> current.append("`n")
                c == '\r' -> current.append("`r")
                c == '\t' -> current.append("`t")
                isControl(c) || loneSurrogate(text, i) -> {
                    flush()
                    parts += "Chr(${c.code})"
                }
                else -> current.append(c)
            }
        }
        flush()
        return when {
            parts.isEmpty() -> "\"\""
            parts.size == 1 && !parts[0].startsWith("Chr(") -> parts[0]
            else -> "(" + (if (parts[0].startsWith("Chr(")) "\"\" . " else "") + parts.joinToString(" . ") + ")"
        }
    }

    /** YAML, double-quoted: `\`, `"`, `\n`, `\r`, `\t`, other control and non-printable characters as `\xNN` / `\uXXXX`. */
    fun yaml(text: String): String = buildString {
        append('"')
        for ((i, c) in text.withIndex()) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\u2028' -> append("\\L")
                c == '\u2029' -> append("\\P")
                c < ' ' || c in '\u007f'..'\u009f' -> append("\\x").append(c.code.toString(16).padStart(2, '0'))
                c == '\ufeff' || c == '\ufffe' || c == '\uffff' || loneSurrogate(text, i) -> append("\\u").append(hex4(c))
                else -> append(c)
            }
        }
        append('"')
    }

    /** Escapes regular-expression metacharacters so the text matches only itself (Maestro's `text` and `id` are regular expressions). */
    fun regexLiteral(text: String): String = buildString {
        for (c in text) {
            if (c in "\\.^$*+?()[]{}|") append('\\')
            append(c)
        }
    }

    /**
     * XPath 1.0 string literal: single-quoted, else double-quoted, else `concat()` when both quote kinds occur.
     * Characters XML cannot hold are dropped (they cannot appear in page source either).
     */
    fun xpath(text: String): String {
        val s = XmlExporter.stripInvalidXmlChars(text)
        return when {
            !s.contains('\'') -> "'$s'"
            !s.contains('"') -> "\"$s\""
            else -> "concat(" + s.split("'").joinToString(", \"'\", ") { "'$it'" } + ")"
        }
    }

    private fun loneSurrogate(text: String, i: Int): Boolean {
        val c = text[i]
        return when {
            Character.isHighSurrogate(c) -> !(i + 1 < text.length && Character.isLowSurrogate(text[i + 1]))
            Character.isLowSurrogate(c) -> !(i > 0 && Character.isHighSurrogate(text[i - 1]))
            else -> false
        }
    }
}
