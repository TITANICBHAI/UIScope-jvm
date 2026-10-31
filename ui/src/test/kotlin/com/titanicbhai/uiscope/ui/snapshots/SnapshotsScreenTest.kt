package com.titanicbhai.uiscope.ui.snapshots

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.snapshot.LoadedSnapshot
import com.titanicbhai.uiscope.snapshot.RetentionResult
import com.titanicbhai.uiscope.snapshot.RetentionPolicy
import com.titanicbhai.uiscope.snapshot.SnapshotMeta
import com.titanicbhai.uiscope.snapshot.SnapshotSort
import com.titanicbhai.uiscope.snapshot.SnapshotStore
import com.titanicbhai.uiscope.snapshot.StorageUsage
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import java.time.Instant
import java.nio.file.Path
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P5-1: list, search, pin, delete and open-in-inspector against a fake store - metadata only, no files. */
class SnapshotsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private class Recording {
        var opened: SnapshotMeta? = null
        val messages = ArrayList<String>()
    }

    private class FakeStore(initial: List<SnapshotMeta>) : SnapshotStore {
        var metas = initial
        var updated = 0
        var deleted = 0

        private fun captureFor(meta: SnapshotMeta): Capture = TestTrees.capture(
            roots = listOf(TestTrees.n(identifier = "app:id/root", name = "Root")),
            id = "cap-" + meta.id,
        )

        override suspend fun save(capture: Capture, title: String?, note: String): Outcome<SnapshotMeta> = metas.first().let { Outcome.Success(it) }
        override suspend fun list(query: String?, sort: SnapshotSort): Outcome<List<SnapshotMeta>> {
            val filtered = metas.filter { meta ->
                query == null || listOf(meta.title, meta.note, meta.appLabel, meta.targetLabel).any { it.contains(query, ignoreCase = true) }
            }
            val sorted = when (sort) {
                SnapshotSort.NEWEST -> filtered.sortedByDescending { it.capturedAt }
                SnapshotSort.OLDEST -> filtered.sortedBy { it.capturedAt }
                SnapshotSort.TITLE -> filtered.sortedBy { it.title }
            }
            return Outcome.Success(sorted)
        }
        override suspend fun get(id: String): Outcome<SnapshotMeta> = Outcome.Success(metas.first { it.id == id })
        override suspend fun load(id: String): Outcome<LoadedSnapshot> {
            val meta = metas.first { it.id == id }
            return Outcome.Success(LoadedSnapshot(meta, captureFor(meta), screenshotPath = null))
        }
        override suspend fun update(id: String, title: String?, note: String?, pinned: Boolean?): Outcome<SnapshotMeta> {
            updated++
            val old = metas.first { it.id == id }
            val changed = old.copy(title = title ?: old.title, note = note ?: old.note, pinned = pinned ?: old.pinned)
            metas = metas.map { if (it.id == id) changed else it }
            return Outcome.Success(changed)
        }
        override suspend fun delete(id: String): Outcome<Unit> {
            deleted++
            metas = metas.filter { it.id != id }
            return Outcome.Success(Unit)
        }
        override suspend fun enforce(policy: RetentionPolicy, protect: Set<String>): Outcome<RetentionResult> = Outcome.Success(RetentionResult(emptyList(), 0))
        override suspend fun usage(): Outcome<StorageUsage> = Outcome.Success(StorageUsage(metas.size, metas.sumOf { it.bytes }, 0, 0))
        override suspend fun clearAll(includePinned: Boolean): Outcome<Int> = Outcome.Success(0)
    }

    private fun meta(id: String, title: String, capturedAt: Instant) = SnapshotMeta(
        id = id,
        title = title,
        note = "",
        pinned = false,
        createdAt = capturedAt,
        capturedAt = capturedAt,
        targetType = com.titanicbhai.uiscope.snapshot.SnapshotTargetType.ANDROID,
        targetLabel = "Pixel 7",
        appLabel = "com.example",
        nodeCount = 12,
        screenshotFile = null,
        captureFile = "capture.json",
        bytes = 4096,
    )

    private fun setContent(store: FakeStore): Recording {
        val r = Recording()
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                SnapshotsScreen(
                    store = store,
                    callbacks = SnapshotCallbacks(
                        onMessage = { r.messages += it },
                        onOpenInInspector = { r.opened = it.meta },
                    ),
                )
            }
        }
        return r
    }

    @Test
    fun `every snapshot is listed with its metadata`() {
        val store = FakeStore(listOf(meta("s1", "Login page", Instant.parse("2026-10-31T10:00:00Z")), meta("s2", "Settings screen", Instant.parse("2026-10-31T11:00:00Z"))))
        setContent(store)
        rule.onNodeWithText("Login page").assertIsDisplayed()
        rule.onNodeWithText("Settings screen").assertIsDisplayed()
        rule.onAllNodesWithText("Pixel 7 - com.example", substring = true).assertCountEquals(2)
    }

    @Test
    fun `the search filters the list`() {
        val store = FakeStore(listOf(meta("s1", "Login page", Instant.parse("2026-10-31T10:00:00Z")), meta("s2", "Settings screen", Instant.parse("2026-10-31T11:00:00Z"))))
        setContent(store)
        rule.onNodeWithTag("snapshots.search").performTextInput("settings")
        rule.waitForIdle()
        rule.onAllNodesWithText("Login page").assertCountEquals(0)
        rule.onNodeWithText("Settings screen").assertIsDisplayed()
    }

    @Test
    fun `pin reports through the store and selection opens the preview`() {
        val store = FakeStore(listOf(meta("s1", "Login page", Instant.parse("2026-10-31T10:00:00Z"))))
        val r = setContent(store)
        rule.onAllNodesWithText("Pin")[0].performClick()
        rule.waitForIdle()
        assertEquals(1, store.updated)
        rule.onNodeWithText("Login page").performClick()
        rule.waitForIdle()
        // The row subtitle and the preview both carry it.
        rule.onAllNodesWithText("12 elements", substring = true).assertCountEquals(2)
        rule.onNodeWithText("Open in Inspector").performClick()
        rule.waitForIdle()
        assertEquals("s1", r.opened?.id)
    }

    @Test
    fun `delete asks for confirmation and reports through the store`() {
        val store = FakeStore(listOf(meta("s1", "Login page", Instant.parse("2026-10-31T10:00:00Z"))))
        val r = setContent(store)
        rule.onNodeWithText("Login page").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Delete")[0].performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Delete")[1].performClick()
        rule.waitForIdle()
        assertEquals(1, store.deleted)
        assertTrue(r.messages.any { it.contains("deleted") })
    }
}
