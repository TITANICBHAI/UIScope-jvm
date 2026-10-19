package com.titanicbhai.uiscope.process

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.error.Remedy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class SystemProcessRunner(
    private val dispatchers: DispatcherProvider,
    private val logger: Logger = Logger.getLogger("uiscope.process"),
) : ProcessRunner {

    override suspend fun run(
        cmd: List<String>,
        timeout: Duration,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): Outcome<ProcessResult> {
        val process = when (val started = startProcess(cmd, env, logger)) {
            is Outcome.Failure -> return started
            is Outcome.Success -> started.value
        }
        val name = executableName(cmd.first())
        val startedNs = System.nanoTime()
        // Readers own blocking pipe reads; they are NOT children of the caller, because a blocked read cannot be cancelled
        // and would hold the caller hostage. They end when the pipes close (process exit or terminateTree).
        val readers = CoroutineScope(SupervisorJob() + dispatchers.io)
        val stdoutJob = readers.async { drain(process.inputStream) }
        val stderrJob = readers.async { drain(process.errorStream) }
        readers.launch { feedStdin(process, stdin) }
        try {
            val result = withTimeout(timeout.inWholeMilliseconds.coerceAtLeast(1)) {
                val exitCode = process.awaitExit()
                ProcessResult(exitCode, stdoutJob.await(), stderrJob.await(), elapsedMs(startedNs))
            }
            logger.info("process $name exit=${result.exitCode} durationMs=${result.durationMs}")
            return Outcome.Success(result)
        } catch (e: TimeoutCancellationException) {
            logger.warning("process $name timed out after ${timeout.inWholeMilliseconds} ms; killing its tree")
            return Outcome.Failure(
                CodedError(ErrorCodes.PROCESS_TIMEOUT, "'$name' did not finish within $timeout", Remedy.RETRY),
            )
        } finally {
            // Runs on success, timeout and cancellation alike: nothing is left running.
            withContext(NonCancellable + dispatchers.io) {
                terminateTree(process, logger)
                readers.cancel()
            }
        }
    }

    private fun drain(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        } catch (e: IOException) {
            logger.log(Level.FINE, "pipe closed while draining", e)
        }
        return out.toByteArray()
    }

    private fun feedStdin(process: Process, data: ByteArray?) {
        try {
            // Always closed, even with no data, so a child that reads stdin to the end does not wait forever.
            process.outputStream.use { if (data != null) it.write(data) }
        } catch (e: IOException) {
            logger.log(Level.FINE, "stdin closed before all data was written", e)
        }
    }

    private fun elapsedMs(startedNs: Long): Long = (System.nanoTime() - startedNs) / NANOS_PER_MS

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val NANOS_PER_MS = 1_000_000L
    }
}
