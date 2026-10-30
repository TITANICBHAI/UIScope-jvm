package com.titanicbhai.uiscope.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.titanicbhai.uiscope.AppInfo
import com.titanicbhai.uiscope.settings.InMemorySettingsStore
import com.titanicbhai.uiscope.settings.Settings
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P4-8: settings apply instantly through the store, the update check hides in Store builds, diagnostics and Reset work. */
class SettingsScreenTest {

    // The Advanced section sits below the fold on a default window; a tall test window keeps every control clickable.
    @get:Rule
    val rule = createComposeRule()

    /** The Advanced section sits below the fold; scroll the screen's column until the control is in view, then click. */
    private fun scrollTo(text: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        rule.waitForIdle()
    }

    private class FakeClipboard : ClipboardManager {
        var last: String? = null
        override fun setText(text: androidx.compose.ui.text.AnnotatedString) { last = text.text }
        override fun getText(): androidx.compose.ui.text.AnnotatedString? = last?.let { androidx.compose.ui.text.AnnotatedString(it) }
    }

    private fun appInfo(store: Boolean) = AppInfo(
        name = "UIScope", publisher = "TBTechs", developer = "TITANICBHAI", version = "1.0.0",
        buildDate = "2026-10-31", commit = "abc1234",
        repoUrl = "https://github.com/TITANICBHAI/UIScope-jvm",
        releasesApiUrl = "https://api.github.com/repos/TITANICBHAI/UIScope-jvm/releases/latest",
        issuesUrl = "https://github.com/TITANICBHAI/UIScope-jvm/issues",
        privacyUrl = "https://github.com/TITANICBHAI/UIScope-jvm/blob/main/PRIVACY.md",
        docsUrl = "https://github.com/TITANICBHAI/UIScope-jvm#readme",
        supportUrl = "https://github.com/TITANICBHAI/UIScope-jvm/issues",
        isStoreBuild = store,
    )

    private fun setContent(store: InMemorySettingsStore, info: AppInfo = appInfo(false), clipboard: FakeClipboard = FakeClipboard()): InMemorySettingsStore {
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Column {
                        Text("ANCHOR")
                        SettingsScreen(
                            settings = store,
                            appInfo = info,
                            adbLine = "adb (version 1.0.41, via PATH)",
                            snapshots = null,
                            diagnostics = { "DIAGNOSTICS-1.0.0" },
                            onOpenLogs = {},
                            onOpenSnapshots = {},
                        )
                    }
                }
            }
        }
        return store
    }

    @Test
    fun `changing the theme applies to the store without a restart`() {
        val store = setContent(InMemorySettingsStore())
        assertEquals(ThemeMode.SYSTEM, store.settings.value.theme)
        rule.onNodeWithText("Dark").performClick()
        rule.waitForIdle()
        assertEquals(ThemeMode.DARK, store.settings.value.theme, "the theme applies instantly")
        rule.onNodeWithText("Light").performClick()
        rule.waitForIdle()
        assertEquals(ThemeMode.LIGHT, store.settings.value.theme)
    }

    @Test
    fun `the update check control is hidden in a store build and shown otherwise`() {
        setContent(InMemorySettingsStore(), appInfo(true))
        assertEquals(0, rule.onAllNodesWithText("Check for updates on startup").fetchSemanticsNodes().size)
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                SettingsScreen(
                    settings = InMemorySettingsStore(), appInfo = appInfo(false), adbLine = null,
                    snapshots = null, diagnostics = { "" }, onOpenLogs = {}, onOpenSnapshots = {},
                )
            }
        }
        rule.onNodeWithText("Check for updates on startup").assertExists()
    }
    @Test
    fun `copy diagnostics puts the version on the clipboard`() {
        val clipboard = FakeClipboard()
        setContent(InMemorySettingsStore(), info = appInfo(false), clipboard = clipboard)
        scrollTo("Copy diagnostics")
        rule.onNodeWithText("Copy diagnostics").performClick()
        rule.waitForIdle()
        val copied = clipboard.last
        // The shell passes the real diagnostics builder; the test's stub returns a version-carrying string.
        assertEquals("DIAGNOSTICS-1.0.0", copied, "copy diagnostics puts the builder's output on the clipboard")
    }

    @Test
    fun `reset after confirmation restores the defaults`() {
        val store = InMemorySettingsStore()
        store.update { it.copy(theme = ThemeMode.DARK, liveIntervalSeconds = 9, autoSaveSnapshots = true) }
        setContent(store)
        scrollTo("Reset all settings")
        rule.onNodeWithText("Reset all settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Reset").performClick()
        rule.waitForIdle()
        assertEquals(Settings(), store.settings.value, "every setting is back to its default")
    }

    @Test
    fun `cancelling the reset keeps the changed settings`() {
        val store = InMemorySettingsStore()
        store.update { it.copy(theme = ThemeMode.DARK) }
        setContent(store)
        scrollTo("Reset all settings")
        rule.onNodeWithText("Reset all settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(ThemeMode.DARK, store.settings.value.theme)
    }
}
