package com.titanicbhai.uiscope.ui.compare

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.model.ScreenInfo
import com.titanicbhai.uiscope.snapshot.LoadedSnapshot
import com.titanicbhai.uiscope.snapshot.RetentionResult
import com.titanicbhai.uiscope.snapshot.RetentionPolicy
import com.titanicbhai.uiscope.snapshot.SnapshotMeta
import com.titanicbhai.uiscope.snapshot.SnapshotSort
import com.titanicbhai.uiscope.snapshot.SnapshotStore
import com.titanicbhai.uiscope.snapshot.StorageUsage
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import java.nio.file.Path
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P6-2: pick two captures, run the engine, browse by chip, locate on both sides, export the diff. */
class CompareScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private class Recording {
        val messages = ArrayList<String>()
        val saved = ArrayList<Pair<String, String>>()
    }

    private class FakeStore : SnapshotStore {
        lateinit var before: Capture
        lateinit var after: Capture

        private fun meta(id: String, title: String) = SnapshotMeta(
            id = id,
            title = title,
            note = "",
            pinned = false,
            createdAt = Instant.EPOCH,
            capturedAt = Instant.EPOCH,
            targetType = com.titanicbhai.uiscope.snapshot.SnapshotTargetType.ANDROID,
            targetLabel = "Pixel 7",
            appLabel = "com.example",
            nodeCount = 3,
            screenshotFile = null,
            captureFile = "capture.json",
            bytes = 4096,
        )

        private val metas = listOf(meta("snap-a", "Before snap"), meta("snap-b", "After snap"))

        override suspend fun list(query: String?, sort: SnapshotSort) = Outcome.Success(metas)
        override suspend fun get(id: String) = Outcome.Success(metas.first { it.id == id })
        override suspend fun load(id: String): Outcome<LoadedSnapshot> = Outcome.Success(
            LoadedSnapshot(metas.first { it.id == id }, if (id == "snap-a") before else after, screenshotPath = null),
        )
        override suspend fun save(capture: Capture, title: String?, note: String) = Outcome.Success(metas.first())
        override suspend fun update(id: String, title: String?, note: String?, pinned: Boolean?) = Outcome.Success(metas.first())
        override suspend fun delete(id: String) = Outcome.Success(Unit)
        override suspend fun enforce(policy: RetentionPolicy, protect: Set<String>) = Outcome.Success(RetentionResult(emptyList(), 0))
        override suspend fun usage() = Outcome.Success(StorageUsage(2, 8192, 0, 0))
        override suspend fun clearAll(includePinned: Boolean) = Outcome.Success(0)
    }

    private fun setContent(): Recording {
        val store = FakeStore()
        // Before: a (text A), panel (textless, moved afterwards), gone (removed).
        store.before = TestTrees.capture(
            roots = listOf(
                TestTrees.n(
                    className = "android.view.View",
                    identifier = "app:id/root",
                    children = listOf(
                        TestTrees.n(className = "android.widget.TextView", identifier = "app:id/a", text = "A", bounds = Bounds(10, 10, 50, 30)),
                        TestTrees.n(className = "android.view.View", bounds = Bounds(100, 100, 200, 100)),
                        TestTrees.n(className = "android.widget.TextView", identifier = "app:id/gone", text = "Gone", bounds = Bounds(5, 5, 20, 20)),
                    ),
                ),
            ),
            id = "cap-before",
            screen = ScreenInfo(width = 1080, height = 2400),
        )
        // After: fresh (added), a (text changed to A2), panel (textless, moved to a different path and offset).
        store.after = TestTrees.capture(
            roots = listOf(
                TestTrees.n(
                    className = "android.view.View",
                    identifier = "app:id/root",
                    children = listOf(
                        TestTrees.n(className = "android.widget.TextView", identifier = "app:id/fresh", text = "Fresh", bounds = Bounds(1, 1, 10, 10)),
                        TestTrees.n(className = "android.widget.TextView", identifier = "app:id/a", text = "A2", bounds = Bounds(10, 10, 50, 30)),
                        TestTrees.n(className = "android.view.View", bounds = Bounds(105, 105, 200, 100)),
                    ),
                ),
            ),
            id = "cap-after",
            screen = ScreenInfo(width = 1080, height = 2400),
        )
        val r = Recording()
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                CompareScreen(
                    store = store,
                    liveCapture = null,
                    callbacks = CompareCallbacks(
                        onMessage = { r.messages += it },
                        saveDiff = { name, bytes ->
                            r.saved += name to bytes.toString(Charsets.UTF_8)
                            Outcome.Success(Unit)
                        },
                    ),
                )
            }
        }
        return r
    }

    @Test
    fun `two snapshots compare into the four categories`() {
        setContent()
        rule.onAllNodesWithText("Pick...")[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Before snap", substring = true).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Pick...")[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("After snap", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Compare").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("3 matched - 1 added - 1 removed").assertIsDisplayed()
    }

    @Test
    fun `the chips filter and a click locates the element on both sides`() {
        setContent()
        rule.onAllNodesWithText("Pick...")[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Before snap", substring = true).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Pick...")[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("After snap", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Compare").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Changed").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("app:id/a").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("text: A -> A2").assertIsDisplayed()
        rule.onNodeWithText("Moved").performClick()
        rule.waitForIdle()
        // The textless panel matched by bounds overlap at a different path (pass 4).
        rule.onNodeWithText("android.view.View (moved)").assertIsDisplayed()
    }

    @Test
    fun `the diff exports as JSON with the paths in it`() {
        val r = setContent()
        rule.onAllNodesWithText("Pick...")[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Before snap", substring = true).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Pick...")[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("After snap", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Compare").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Export JSON").performClick()
        rule.waitForIdle()
        assertEquals(1, r.saved.size)
        assertEquals("uiscope-diff.json", r.saved[0].first)
        assertTrue(r.saved[0].second.contains("app:id/a"), "the JSON carries the matched identifier: ${r.saved[0].second.take(240)}")
        assertTrue(r.saved[0].second.contains(ElementPath.root(0).child(0).toString()), "paths are structural")
    }
}
