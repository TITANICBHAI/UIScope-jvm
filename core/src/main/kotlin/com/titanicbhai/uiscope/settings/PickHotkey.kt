package com.titanicbhai.uiscope.settings

/**
 * The global "start picking" shortcut as the settings store it (`Ctrl+Alt+P`) and as the Windows helper wants it
 * (modifiers `ctrl+alt`, key `P`). Parsing lives here, away from the UI, so it is testable and the shortcut can never be registered half-understood.
 */
object PickHotkey {
    private val MODIFIERS = mapOf(
        "ctrl" to "ctrl",
        "control" to "ctrl",
        "alt" to "alt",
        "shift" to "shift",
        "win" to "win",
        "windows" to "win",
    )

    /**
     * Splits [text] into (modifiers, key), e.g. `"Ctrl+Alt+P"` to `("ctrl+alt", "P")`; null when there is no modifier (a bare key would
     * steal that key from every program), no key, or a word that is not a modifier.
     */
    fun parse(text: String): Pair<String, String>? {
        val parts = text.split('+').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val modifiers = parts.dropLast(1).map { MODIFIERS[it.lowercase()] ?: return null }.distinct()
        return modifiers.joinToString("+") to parts.last()
    }
}
