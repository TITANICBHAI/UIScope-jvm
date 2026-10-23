package com.titanicbhai.uiscope.testing

import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.process.ProcessResult
import com.titanicbhai.uiscope.process.ProcessRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/** A [ProcessRunner] that answers from a function instead of starting anything. Records every call. */
class FakeProcessRunner(private val handler: (cmd: List<String>) -> Outcome<ProcessResult>?) : ProcessRunner {
    val calls = CopyOnWriteArrayList<List<String>>()
    val timeouts = CopyOnWriteArrayList<Duration>()

    override suspend fun run(cmd: List<String>, timeout: Duration, stdin: ByteArray?, env: Map<String, String>): Outcome<ProcessResult> {
        calls += cmd
        timeouts += timeout
        return handler(cmd) ?: Outcome.Failure(CodedError(ErrorCodes.PROCESS_START_FAILED, "no fake answer for ${cmd.joinToString(" ")}"))
    }

    companion object {
        fun ok(stdout: String = "", stderr: String = "", exitCode: Int = 0): Outcome<ProcessResult> =
            Outcome.Success(ProcessResult(exitCode, stdout.toByteArray(Charsets.UTF_8), stderr.toByteArray(Charsets.UTF_8), 1))

        fun bytes(stdout: ByteArray, exitCode: Int = 0): Outcome<ProcessResult> = Outcome.Success(ProcessResult(exitCode, stdout, ByteArray(0), 1))

        fun timedOut(): Outcome<ProcessResult> = Outcome.Failure(CodedError(ErrorCodes.PROCESS_TIMEOUT, "timed out"))
    }
}
