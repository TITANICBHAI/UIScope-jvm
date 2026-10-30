package com.titanicbhai.uiscope.snapshot

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetentionPlannerTest {
    private fun meta(id: String, minute: Int, bytes: Long = 100, pinned: Boolean = false) = SnapshotMeta(
        id, "t$id", "", pinned, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(minute * 60L), Instant.parse("2025-12-31T00:00:00Z"), SnapshotTargetType.ANDROID, "m", "a", 1, "screenshot.png", "capture.json", bytes,
    )

    private fun ids(list: List<SnapshotMeta>) = list.map { it.id }

    @Test
    fun `nothing is removed while both limits hold`() {
        val all = (1..5).map { meta("s$it", it) }
        assertEquals(emptyList(), RetentionPlanner.victims(all, RetentionPolicy(5, 500)))
    }

    @Test
    fun `the count limit removes the oldest first`() {
        val all = listOf(meta("c", 3), meta("a", 1), meta("d", 4), meta("b", 2))
        assertEquals(listOf("a", "b"), ids(RetentionPlanner.victims(all, RetentionPolicy(2, Long.MAX_VALUE))))
    }

    @Test
    fun `the byte limit removes the oldest first until it holds`() {
        val all = listOf(meta("a", 1, 400), meta("b", 2, 400), meta("c", 3, 400))
        assertEquals(listOf("a"), ids(RetentionPlanner.victims(all, RetentionPolicy(10, 800))))
        assertEquals(listOf("a", "b"), ids(RetentionPlanner.victims(all, RetentionPolicy(10, 799))))
    }

    @Test
    fun `whichever limit is hit first decides`() {
        val all = listOf(meta("a", 1, 10), meta("b", 2, 10), meta("c", 3, 1000))
        // Count limit 2 is hit first (3 snapshots, 1020 bytes <= 5000): only the oldest goes.
        assertEquals(listOf("a"), ids(RetentionPlanner.victims(all, RetentionPolicy(2, 5000))))
        // Byte limit 1000 is hit first (3 <= 10 snapshots, 1020 bytes): the two oldest (10 + 10 bytes) must go to reach exactly 1000.
        assertEquals(listOf("a", "b"), ids(RetentionPlanner.victims(all, RetentionPolicy(10, 1000))))
    }

    @Test
    fun `pinned snapshots are neither counted nor removed`() {
        val all = listOf(meta("p1", 1, 10_000, pinned = true), meta("p2", 2, 10_000, pinned = true), meta("a", 3), meta("b", 4))
        assertEquals(emptyList(), RetentionPlanner.victims(all, RetentionPolicy(2, 300)))
        assertEquals(listOf("a"), ids(RetentionPlanner.victims(all, RetentionPolicy(1, 300))))
        assertEquals(listOf("a", "b"), ids(RetentionPlanner.victims(all, RetentionPolicy(0, 300))))
    }

    @Test
    fun `a protected snapshot is kept even when it alone exceeds the limit`() {
        val all = listOf(meta("old", 1, 100), meta("new", 2, 5000))
        assertEquals(listOf("old"), ids(RetentionPlanner.victims(all, RetentionPolicy(10, 1000), protect = setOf("new"))))
    }

    @Test
    fun `equal times fall back to the id so the choice is deterministic`() {
        val all = listOf(meta("b", 1), meta("a", 1), meta("c", 1))
        assertEquals(listOf("a"), ids(RetentionPlanner.victims(all, RetentionPolicy(2, Long.MAX_VALUE))))
    }

    @Test
    fun `negative limits are refused`() {
        assertFailsWith<IllegalArgumentException> { RetentionPolicy(-1, 0) }
        assertFailsWith<IllegalArgumentException> { RetentionPolicy(0, -1) }
    }
}
