package com.titanicbhai.uiscope.storage

import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.settings.Settings
import com.titanicbhai.uiscope.snapshot.RetainingSnapshotStore
import com.titanicbhai.uiscope.snapshot.RetentionPolicy
import com.titanicbhai.uiscope.snapshot.retentionPolicy
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.withTempDir
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** B7 regression (P5-3): automatic saving can no longer fill the disk. */
class RetentionStoreTest {
    @Test
    fun `settings map to the policy`() {
        assertEquals(RetentionPolicy(42, 5_000_000_000L), Settings(retentionCount = 42, retentionBytes = 5_000_000_000L).retentionPolicy())
    }

    @Test
    fun `ten minutes of auto saves every two seconds stay under both caps at every step`() {
        withTempDir { dir ->
            val rig = SnapshotRig(dir)
            val errors = mutableListOf<AppError>()
            val policy = RetentionPolicy(maxCount = 25, maxBytes = 25 * 3_000L)
            val store = RetainingSnapshotStore(rig.store, { policy }, { errors += it })
            var peakCount = 0
            var peakBytes = 0L
            repeat(300) { i ->
                rig.clock.advance(java.time.Duration.ofSeconds(2))
                val saved = blocking1 { store.save(rig.cap("frame $i")) }.assertSuccess()
                val usage = blocking1 { store.usage() }.assertSuccess()
                peakCount = maxOf(peakCount, usage.count)
                peakBytes = maxOf(peakBytes, usage.bytes)
                assertTrue(usage.count <= policy.maxCount, "count ${usage.count} after save $i")
                assertTrue(usage.bytes <= policy.maxBytes, "bytes ${usage.bytes} after save $i")
                assertEquals(saved.id, blocking1 { store.list() }.assertSuccess().first().id, "the newest snapshot must survive its own save")
            }
            assertEquals(emptyList(), errors)
            assertEquals(25, peakCount)
            val onDisk = Files.list(rig.snapshots).use { s -> s.filter { !it.fileName.toString().startsWith(".") }.count() }
            assertEquals(25L, onDisk, "folders on disk must match the list; deleted snapshots must not leave files")
        }
    }

    @Test
    fun `pinned snapshots stay while automatic ones are trimmed`() {
        withTempDir { dir ->
            val rig = SnapshotRig(dir)
            val store = RetainingSnapshotStore(rig.store, { RetentionPolicy(3, Long.MAX_VALUE) }, { })
            val pinned = blocking1 { store.save(rig.cap("keep")) }.assertSuccess()
            blocking1 { store.update(pinned.id, pinned = true) }.assertSuccess()
            repeat(10) { blocking1 { store.save(rig.cap("auto $it")) }.assertSuccess() }
            val list = blocking1 { store.list() }.assertSuccess()
            assertEquals(4, list.size)
            assertTrue(list.any { it.id == pinned.id })
        }
    }

    @Test
    fun `lowering the limit applies at start-up and a new limit applies to the next save`() {
        withTempDir { dir ->
            val rig = SnapshotRig(dir)
            var policy = RetentionPolicy(100, Long.MAX_VALUE)
            val store = RetainingSnapshotStore(rig.store, { policy }, { })
            repeat(10) { blocking1 { store.save(rig.cap("s$it")) }.assertSuccess() }
            policy = RetentionPolicy(4, Long.MAX_VALUE)
            val result = blocking1 { store.enforceNow() }.assertSuccess()
            assertEquals(6, result.deleted.size)
            assertEquals(4, blocking1 { store.list() }.assertSuccess().size)
            policy = RetentionPolicy(2, Long.MAX_VALUE)
            blocking1 { store.save(rig.cap("next")) }.assertSuccess()
            assertEquals(2, blocking1 { store.list() }.assertSuccess().size)
        }
    }
}
