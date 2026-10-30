package com.titanicbhai.uiscope.snapshot

import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.settings.Settings

/** The retention limits the user configured (Settings → Storage). */
fun Settings.retentionPolicy(): RetentionPolicy = RetentionPolicy(maxCount = retentionCount, maxBytes = retentionBytes)

/**
 * Enforces the retention limits after every save (plan P5-3, B7). The limits are read through [policy] each time, so a change in
 * Settings applies to the next save. The snapshot just saved is protected: it is never removed by its own save.
 *
 * A failure of the cleanup does not fail the save (the snapshot exists); it is reported through [onRetentionError] so it is logged
 * or shown, never swallowed.
 */
class RetainingSnapshotStore(
    private val inner: SnapshotStore,
    private val policy: () -> RetentionPolicy,
    private val onRetentionError: (AppError) -> Unit,
) : SnapshotStore by inner {

    override suspend fun save(capture: Capture, title: String?, note: String): Outcome<SnapshotMeta> {
        val saved = inner.save(capture, title, note)
        if (saved is Outcome.Success) {
            val cleanup = inner.enforce(policy(), protect = setOf(saved.value.id))
            if (cleanup is Outcome.Failure) onRetentionError(cleanup.error)
        }
        return saved
    }

    /** Applies the current limits now; call at start-up and when the limits are lowered. */
    suspend fun enforceNow(): Outcome<RetentionResult> = inner.enforce(policy())
}
