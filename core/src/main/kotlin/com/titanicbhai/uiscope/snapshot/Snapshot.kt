package com.titanicbhai.uiscope.snapshot

import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.Capture
import java.time.Instant

enum class SnapshotTargetType { ANDROID, WINDOWS }

/**
 * What the database knows about a snapshot (plan §3.5: metadata only; lists never read capture files).
 *
 * @property id Random identifier, also the folder name under `snapshots/`. Not the capture's id (the same capture can be saved twice).
 * @property targetLabel Device model or window title: what the list shows as "target". Never a serial.
 * @property appLabel Foreground package (Android) or process name (Windows).
 * @property bytes Size of `capture.json` plus the screenshot.
 */
data class SnapshotMeta(
    val id: String,
    val title: String,
    val note: String,
    val pinned: Boolean,
    /** When the snapshot entered this store (retention order). */
    val createdAt: Instant,
    /** When the screen was captured (what the list shows as the time). */
    val capturedAt: Instant,
    val targetType: SnapshotTargetType,
    val targetLabel: String,
    val appLabel: String,
    val nodeCount: Int,
    val screenshotFile: String?,
    val captureFile: String,
    val bytes: Long,
)

/** A snapshot read back from disk: its metadata, the capture, and the absolute path of the screenshot (null when it has none). */
data class LoadedSnapshot(val meta: SnapshotMeta, val capture: Capture, val screenshotPath: java.nio.file.Path?)

enum class SnapshotSort { NEWEST, OLDEST, TITLE }

data class StorageUsage(val count: Int, val bytes: Long, val pinnedCount: Int, val pinnedBytes: Long)

data class RetentionResult(val deleted: List<String>, val freedBytes: Long)

/**
 * The retention limits as a plain value (plan P1-7): the store never reads `Settings`; callers pass the limits.
 * **Pinned snapshots are exempt**: they are neither counted against the limits nor ever deleted.
 */
data class RetentionPolicy(val maxCount: Int, val maxBytes: Long) {
    init {
        require(maxCount >= 0 && maxBytes >= 0) { "Retention limits cannot be negative" }
    }

    companion object {
        val DEFAULT = RetentionPolicy(maxCount = 200, maxBytes = 1_000_000_000L)
    }
}

/** Decides which snapshots a [RetentionPolicy] removes. Pure, so the rule is tested without files. */
object RetentionPlanner {
    /**
     * Oldest first, among unpinned snapshots that are not in [protect]: remove until both the count and the byte limit hold.
     * Protected snapshots (the one just saved) still count towards the totals but are never chosen, so a snapshot larger than the byte
     * limit is kept rather than deleted the moment it is saved.
     */
    fun victims(all: List<SnapshotMeta>, policy: RetentionPolicy, protect: Set<String> = emptySet()): List<SnapshotMeta> {
        val counted = all.filter { !it.pinned }
        var count = counted.size
        var bytes = counted.sumOf { it.bytes }
        val candidates = counted.filter { it.id !in protect }.sortedWith(compareBy({ it.createdAt }, { it.id }))
        val chosen = ArrayList<SnapshotMeta>()
        for (s in candidates) {
            if (count <= policy.maxCount && bytes <= policy.maxBytes) break
            chosen += s
            count--
            bytes -= s.bytes
        }
        return chosen
    }
}

/**
 * Where snapshots live (plan §3.1 port). Implemented by the engine; state holders and the UI see only this.
 * Every method is main-safe: implementations do their file and database work off the calling thread.
 */
interface SnapshotStore {
    /** Saves [capture] on purpose (plan §4.7). A screenshot is copied from `capture.screenshot.file` (an absolute path). */
    suspend fun save(capture: Capture, title: String? = null, note: String = ""): Outcome<SnapshotMeta>

    /** Metadata only. [query] matches title, note, app and target (case-insensitive). Never opens a capture file. */
    suspend fun list(query: String? = null, sort: SnapshotSort = SnapshotSort.NEWEST): Outcome<List<SnapshotMeta>>

    suspend fun get(id: String): Outcome<SnapshotMeta>

    /** Reads `capture.json` and resolves the screenshot path; a damaged or missing file is a typed error. */
    suspend fun load(id: String): Outcome<LoadedSnapshot>

    suspend fun update(id: String, title: String? = null, note: String? = null, pinned: Boolean? = null): Outcome<SnapshotMeta>

    /** Removes the database row and the snapshot's files. */
    suspend fun delete(id: String): Outcome<Unit>

    /** Applies [policy] and deletes what it selects, files included. [protect] ids are never chosen. */
    suspend fun enforce(policy: RetentionPolicy, protect: Set<String> = emptySet()): Outcome<RetentionResult>

    suspend fun usage(): Outcome<StorageUsage>

    /** Deletes every snapshot ([includePinned] false keeps pinned ones). Returns how many were deleted. */
    suspend fun clearAll(includePinned: Boolean = true): Outcome<Int>
}
