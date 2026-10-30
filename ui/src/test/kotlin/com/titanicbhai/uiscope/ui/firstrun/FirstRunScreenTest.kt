package com.titanicbhai.uiscope.ui.firstrun

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.AdbDevice
import com.titanicbhai.uiscope.model.DeviceState
import com.titanicbhai.uiscope.ports.AdbStatus
import com.titanicbhai.uiscope.settings.InspectTarget
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.state.AndroidInspectUi
import com.titanicbhai.uiscope.state.AndroidState
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P4-7: the first-run quick checks show REAL adb status, the consent dialog precedes any download, and skipping finishes. */
class FirstRunScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private class Recording {
        var rechecked = 0
        var finished = 0
        val targets = ArrayList<InspectTarget>()
        val browsed = ArrayList<Int>()
    }

    private val missing = AndroidInspectUi(state = AndroidState.AdbMissing)
    private val found = AndroidInspectUi(
        state = AndroidState.NoDevice,
        adb = AdbStatus("adb", "1.0.41", "PATH"),
        devices = listOf(AdbDevice("SERIAL1", DeviceState.DEVICE, "Pixel 7", "p", "1")),
    )

    private fun download(consent: Outcome<String> = Outcome.Success("UIScope will download Google's official platform-tools.")): AdbDownloadActions =
        AdbDownloadActions(
            consentText = { consent },
            install = { _ -> Outcome.Success(Path.of("installed")) },
        )

    private fun setContent(ui: AndroidInspectUi, actions: AdbDownloadActions = download(), windowsPc: Boolean = false): Recording {
        val r = Recording()
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                FirstRunScreen(
                    ui = ui,
                    target = InspectTarget.ANDROID,
                    windowsPcAvailable = windowsPc,
                    storeBuild = false,
                    checkForUpdates = true,
                    download = actions,
                    callbacks = FirstRunCallbacks(
                        onRecheck = { r.rechecked++ },
                        onSetTarget = { r.targets += it },
                        onBrowseAdb = { r.browsed += 1 },
                        onFinish = { r.finished++ },
                    ),
                )
            }
        }
        // Step 3 holds the quick checks.
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Next").performClick()
        rule.waitForIdle()
        return r
    }

    @Test
    fun `step 3 shows not-found with download, browse and self-install when adb is missing`() {
        val r = setContent(missing)
        rule.onNodeWithText("ADB not found").assertIsDisplayed()
        rule.onNodeWithText("Download platform-tools").assertIsDisplayed()
        rule.onNodeWithText("Browse...").assertIsDisplayed()
        rule.onNodeWithText("I'll install it myself").assertIsDisplayed()
        rule.onNodeWithText("Re-check").performClick()
        rule.waitForIdle()
        assertEquals(1, r.rechecked)
    }

    @Test
    fun `step 3 shows the real adb status when it is found`() {
        setContent(found)
        rule.onNodeWithText("ADB found: adb (version 1.0.41, via PATH)", substring = true).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("ADB not found").fetchSemanticsNodes().size)
    }

    @Test
    fun `the download opens the consent dialog first and installing re-checks`() {
        val r = setContent(missing)
        rule.onNodeWithText("Download platform-tools").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("UIScope will download Google's official platform-tools.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Download and install").performClick()
        rule.waitForIdle()
        assertEquals(1, r.rechecked, "a successful install triggers the re-check")
    }

    @Test
    fun `an unavailable download explains itself instead of opening the dialog`() {
        setContent(missing, actions = download(Outcome.Failure(CodedError(ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE))))
        rule.onNodeWithText("Download platform-tools").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Downloading is not available right now (ADB_DOWNLOAD_UNAVAILABLE).", substring = true).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Download and install").fetchSemanticsNodes().size)
    }

    @Test
    fun `browse reports and skip finishes`() {
        val r = setContent(missing)
        rule.onNodeWithText("Browse...").performClick()
        rule.waitForIdle()
        assertEquals(1, r.browsed.size)
        rule.onNodeWithText("Skip").performClick()
        rule.waitForIdle()
        assertEquals(1, r.finished)
    }

    @Test
    fun `the windows card only exists on windows`() {
        val withWindows = setContent(missing, windowsPc = true)
        // Step 2: navigate back to it and pick the Windows card.
        rule.onNodeWithText("Back").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Windows app").performClick()
        rule.waitForIdle()
        assertEquals(listOf(InspectTarget.WINDOWS), withWindows.targets)
        setContent(missing, windowsPc = false)
        assertEquals(0, rule.onAllNodesWithText("Windows app").fetchSemanticsNodes().size)
    }
}
