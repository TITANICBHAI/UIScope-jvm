package com.titanicbhai.uiscope.settings

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Appendix G10, the core half: defaults, theme resolution (B12), read-after-write, no lost updates. */
class SettingsTest {
    @Test
    fun `defaults are defined once and are sensible`() {
        val s = Settings()
        assertEquals(ThemeMode.SYSTEM, s.theme)
        assertEquals(2, s.liveIntervalSeconds)
        assertEquals("Ctrl+Alt+P", s.pickHotkey)
        assertEquals(200, s.retentionCount)
        assertEquals(1024L * 1024 * 1024, s.retentionBytes)
        assertTrue(s.checkForUpdates && s.pickHotkeyEnabled && s.hideWhilePicking)
        assertFalse(s.autoSaveSnapshots || s.windowMaximized)
        assertNull(s.adbPath)
        assertEquals(Settings(), Settings().sanitized(), "the defaults are already valid")
    }

    @Test
    fun `System follows the operating system and the others do not`() {
        assertTrue(ThemeMode.SYSTEM.isDark(osIsDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(osIsDark = false))
        assertFalse(ThemeMode.LIGHT.isDark(osIsDark = true))
        assertTrue(ThemeMode.DARK.isDark(osIsDark = false))
    }

    @Test
    fun `sanitizing clamps numbers and drops blank text`() {
        val s = Settings(liveIntervalSeconds = 0, retentionCount = 0, retentionBytes = 1, pickHotkey = "", adbPath = " ".repeat(0), lastSerial = "").sanitized()
        assertEquals(1, s.liveIntervalSeconds)
        assertEquals(1, s.retentionCount)
        assertEquals(Settings.MIN_RETENTION_BYTES, s.retentionBytes)
        assertEquals(Settings.DEFAULT_PICK_HOTKEY, s.pickHotkey)
        assertNull(s.adbPath)
        assertNull(s.lastSerial)
        assertEquals(10, Settings(liveIntervalSeconds = 99).sanitized().liveIntervalSeconds)
    }

    @Test
    fun `a change is visible to the very next read`() {
        val store = InMemorySettingsStore()
        store.update { it.copy(theme = ThemeMode.DARK) }
        assertEquals(ThemeMode.DARK, store.settings.value.theme)
        store.update { it.copy(liveIntervalSeconds = 50) }
        assertEquals(10, store.settings.value.liveIntervalSeconds, "values are clamped on the way in")
        store.reset()
        assertEquals(Settings(), store.settings.value)
    }

    @Test
    fun `simultaneous updates never lose a change`() {
        val store = InMemorySettingsStore()
        val threads = 8
        val perThread = 500
        val start = CountDownLatch(1)
        val workers = (1..threads).map {
            thread {
                start.await()
                repeat(perThread) { store.update { s -> s.copy(retentionBytes = s.retentionBytes + 1) } }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(Settings.DEFAULT_RETENTION_BYTES + threads * perThread, store.settings.value.retentionBytes)
    }

    @Test
    fun `the store reports each change to its subclass hook`() {
        var changes = 0
        val store = object : InMemorySettingsStore() {
            override fun changed() {
                changes++
            }
        }
        store.update { it.copy(theme = ThemeMode.LIGHT) }
        store.reset()
        assertEquals(2, changes)
    }
}
