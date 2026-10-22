package com.titanicbhai.uiscope.testing

/** Strings that broke the old code generator (B6) and others like them; every template is tested with all of them. */
object HostileStrings {
    /** Strings every language can represent. */
    val common: Map<String, String> = linkedMapOf(
        "apostrophe" to "Don't allow",
        "double-quote" to "Say \"hi\"",
        "both-quotes" to "He said \"it's\" ok",
        "backslash" to "back\\slash",
        "newline" to "line one\nline two",
        "markup" to "<a & b>",
        "cjk" to "日本語",
        "dollar" to "\$dollar and \${braces}",
        "backtick" to "`tick`",
        "percent" to "100%",
        "empty" to "",
        "tab-cr" to "a\tb\rc",
        "emoji" to "😀 ok",
        "regex-meta" to "a.b*c(d)[e]{f}|g^h\$",
        "smart-quotes" to "it\u2019s \u2018x\u2019",
        "backslash-u" to "\\u0041 \\n",
    )

    /** Control characters: not representable everywhere (XML drops them), but no template may produce broken code for them. */
    val controls: Map<String, String> = linkedMapOf("control-chars" to "a\u0001b\u007fc\u001fd")

    /** An unpaired surrogate, the nastiest case for any encoder. */
    val loneSurrogate: Map<String, String> = linkedMapOf("lone-surrogate" to "x\uD800y")

    val all: Map<String, String> = common + controls + loneSurrogate
}
