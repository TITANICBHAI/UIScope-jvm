package com.titanicbhai.uiscope.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PickHotkeyTest {
    @Test
    fun `the default shortcut splits into modifiers and key`() {
        assertEquals("ctrl+alt" to "P", PickHotkey.parse(Settings.DEFAULT_PICK_HOTKEY))
    }

    @Test
    fun `spelling, case and spacing do not matter`() {
        assertEquals("ctrl+shift" to "F9", PickHotkey.parse(" control + SHIFT + F9 "))
        assertEquals("win+alt" to "X", PickHotkey.parse("Windows+Alt+X"))
        assertEquals("ctrl" to "P", PickHotkey.parse("Ctrl+Ctrl+P"), "a repeated modifier counts once")
    }

    @Test
    fun `a shortcut without a modifier or without a key is refused`() {
        assertNull(PickHotkey.parse("P"))
        assertNull(PickHotkey.parse("Ctrl+"))
        assertNull(PickHotkey.parse(""))
        assertNull(PickHotkey.parse("   "))
    }

    @Test
    fun `an unknown modifier word is refused instead of being guessed`() {
        assertNull(PickHotkey.parse("Hyper+P"))
        assertNull(PickHotkey.parse("Ctrl+Banana+P"))
    }
}
