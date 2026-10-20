package com.titanicbhai.uiscope.selector

import com.titanicbhai.uiscope.model.ElementIndex

/** The two heuristics of Appendix C: dynamic-looking text and obfuscated identifiers. Both only ever produce warnings. */
object TextHeuristics {
    private const val MAX_STABLE_TEXT_LENGTH = 40
    private val twoDigits = Regex("""\d{2,}""")
    private val clock = Regex("""\d{1,2}:\d{2}""")
    private val date = Regex("""\d{1,4}[/.\-]\d{1,2}[/.\-]\d{1,4}""")
    private val currency = Regex("""[$€£¥₹₽₩₺₫]""")
    private val shortId = Regex("""^[a-z]{1,2}\d*$""")

    /** Text that probably changes between runs: 2+ consecutive digits, a time or date, a currency symbol, `%`, `…`, or more than 40 characters. */
    fun looksDynamic(text: String): Boolean =
        text.length > MAX_STABLE_TEXT_LENGTH ||
            text.contains('%') || text.contains('…') ||
            twoDigits.containsMatchIn(text) || clock.containsMatchIn(text) || date.containsMatchIn(text) || currency.containsMatchIn(text)

    /**
     * Tree-level only: true when the capture has at least [MIN_IDS] distinct identifiers and at least [SHARE] of them (the part after the
     * last `/`) look like `^[a-z]{1,2}\d*$`. One short id never flags anything on its own.
     */
    fun idsLookObfuscated(index: ElementIndex): Boolean {
        val ids = index.nodes.mapNotNullTo(HashSet()) { it.identifier }
        if (ids.size < MIN_IDS) return false
        val short = ids.count { shortId.matches(it.substringAfterLast('/')) }
        return short.toDouble() / ids.size >= SHARE
    }

    const val MIN_IDS = 10
    const val SHARE = 0.6

    /** True for identifiers made only of digits (Windows Win32 control ids). */
    fun isNumericOnly(id: String): Boolean = id.isNotEmpty() && id.all { it in '0'..'9' }
}
