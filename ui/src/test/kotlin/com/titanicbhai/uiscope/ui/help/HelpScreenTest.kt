package com.titanicbhai.uiscope.ui.help

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.titanicbhai.uiscope.AppInfo
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** P4-7: the Help screen carries the welcome (replayable) and the real shortcuts sheet. */
class HelpScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val info = AppInfo(
        name = "UIScope", publisher = "TBTechs", developer = "TITANICBHAI", version = "1.0.0",
        buildDate = "2026-10-31", commit = "abc1234",
        repoUrl = "https://github.com/TITANICBHAI/UIScope-jvm",
        releasesApiUrl = "https://api.github.com/repos/TITANICBHAI/UIScope-jvm/releases/latest",
        issuesUrl = "https://github.com/TITANICBHAI/UIScope-jvm/issues",
        privacyUrl = "https://github.com/TITANICBHAI/UIScope-jvm/blob/main/PRIVACY.md",
        docsUrl = "https://github.com/TITANICBHAI/UIScope-jvm#readme",
        supportUrl = "https://github.com/TITANICBHAI/UIScope-jvm/issues",
        isStoreBuild = false,
    )

    @Test
    fun `the shortcuts sheet lists the real shortcuts and the welcome can be replayed`() {
        var replayed = 0
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                HelpScreen(appInfo = info, onReplayFirstRun = { replayed++ })
            }
        }
        rule.onNodeWithText("Capture / Refresh").assertIsDisplayed()
        rule.onNodeWithText("F5, Ctrl+R").assertIsDisplayed()
        rule.onNodeWithText("Ctrl+Alt+P").assertIsDisplayed()
        rule.onNodeWithText("Run the welcome again").performClick()
        rule.waitForIdle()
        assertEquals(1, replayed)
    }
}
