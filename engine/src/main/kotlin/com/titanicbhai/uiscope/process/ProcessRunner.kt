package com.titanicbhai.uiscope.process

import com.titanicbhai.uiscope.error.Outcome
import kotlin.time.Duration

/** Output of a finished one-shot process. [toString] deliberately never prints the output (it may contain screen text). */
class ProcessResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val durationMs: Long,
) {
    fun stdoutText(): String = stdout.toString(Charsets.UTF_8)

    fun stderrText(): String = stderr.toString(Charsets.UTF_8)

    override fun toString(): String =
        "ProcessResult(exitCode=$exitCode, stdout=${stdout.size} bytes, stderr=${stderr.size} bytes, durationMs=$durationMs)"
}

/**
 * The only way the app starts a short-lived external process (adb, the Windows helper's one-shot commands).
 *
 * Contract:
 * - Arguments are an argument list, never a shell string.
 * - stdout and stderr are drained concurrently and returned separately, binary-safe.
 * - On timeout or coroutine cancellation the whole process tree is killed.
 * - A non-zero exit code is a normal [Outcome.Success]; callers interpret it. Failing to start, or timing out, is a failure.
 */
interface ProcessRunner {
    suspend fun run(
        cmd: List<String>,
        timeout: Duration,
        stdin: ByteArray? = null,
        env: Map<String, String> = emptyMap(),
    ): Outcome<ProcessResult>
}
