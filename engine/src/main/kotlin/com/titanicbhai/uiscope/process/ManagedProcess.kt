package com.titanicbhai.uiscope.process

import com.titanicbhai.uiscope.error.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * A long-lived child process: `adb track-devices` (P2-2) and the Windows helper (P3-2).
 *
 * - [stdoutLines]/[stderrLines] are single-consumer streams: collect each from one place. They complete when the
 *   process closes the stream. stderr keeps only the most recent lines if nobody reads it.
 * - The process is killed (whole tree) when the scope given to [ProcessLauncher.start] is cancelled, or on [kill].
 */
interface ManagedProcess {
    val pid: Long
    val isAlive: Boolean
    val stdoutLines: Flow<String>
    val stderrLines: Flow<String>

    /** Writes [line] plus a newline to the process's stdin (UTF-8). */
    suspend fun writeLine(line: String): Outcome<Unit>

    /** Suspends until the process exits and returns its exit code. */
    suspend fun awaitExit(): Int

    /** Kills the process tree now. Safe to call more than once. */
    fun kill()
}

interface ProcessLauncher {
    /** Starts [cmd] and ties its lifetime to [scope]. */
    fun start(
        cmd: List<String>,
        scope: CoroutineScope,
        env: Map<String, String> = emptyMap(),
    ): Outcome<ManagedProcess>
}
