package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ScreenInfo
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.snapshot.LoadedSnapshot
import com.titanicbhai.uiscope.snapshot.SnapshotMeta
import com.titanicbhai.uiscope.snapshot.SnapshotTargetType
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** P5-1: opening a snapshot reproduces the tree offline under the "not live" banner. */
class SnapshotInspectorTest {

    @get:Rule
    val rule = createComposeRule()

    private fun loaded(): LoadedSnapshot {
        val capture = TestTrees.capture(
            roots = listOf(
                TestTrees.n(
                    className = "Window",
                    identifier = "app:id/root",
                    name = "Root",
                    bounds = Bounds(0, 0, 800, 600),
                    children = listOf(
                        TestTrees.n(className = "Button", identifier = "app:id/ok", name = "OK", text = "OK", bounds = Bounds(400, 400, 200, 60)),
                    ),
                ),
            ),
            id = "snap-cap-1",
            screen = ScreenInfo(width = 800, height = 600, originX = 100, originY = 50),
            target = Target.Windows(
                com.titanicbhai.uiscope.model.WindowInfo(title = "Notepad", processName = "notepad.exe", pid = 7, isElevated = false, hwnd = "0x00000001"),
            ),
        )
        val meta = SnapshotMeta(
            id = "snap-1",
            title = "Notepad - the save",
            note = "the bug",
            pinned = false,
            createdAt = Instant.EPOCH,
            capturedAt = Instant.EPOCH,
            targetType = SnapshotTargetType.WINDOWS,
            targetLabel = "Notepad",
            appLabel = "notepad.exe",
            nodeCount = 2,
            screenshotFile = null,
            captureFile = "capture.json",
            bytes = 1024,
        )
        return LoadedSnapshot(meta, capture, screenshotPath = null)
    }

    @Test
    fun `the banner, the tree and the close action are there`() {
        var closed = 0
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                SnapshotInspector(loaded = loaded(), onClose = { closed++ })
            }
        }
        rule.onNodeWithText("not live", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Notepad - the save", substring = true).assertIsDisplayed()
        rule.onNodeWithText("OK").assertIsDisplayed()
        rule.onNodeWithText("Close snapshot").performClick()
        rule.waitForIdle()
        assertEquals(1, closed)
    }
}
