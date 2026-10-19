package com.titanicbhai.uiscope.process

import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Only the file name of the executable: arguments (serials, paths, window titles) never reach a log line. */
internal fun executableName(command: String): String = command.substringAfterLast('/').substringAfterLast('\\')

/** The single place in the app that calls `ProcessBuilder.start` (plan §3.4, guardrail in P3-9). */
internal fun startProcess(cmd: List<String>, env: Map<String, String>, logger: Logger): Outcome<Process> {
    if (cmd.isEmpty() || cmd.first().isBlank()) {
        return Outcome.Failure(CodedError(ErrorCodes.PROCESS_START_FAILED, "No command to run"))
    }
    val name = executableName(cmd.first())
    return try {
        val builder = ProcessBuilder(cmd)
        builder.environment().putAll(env)
        Outcome.Success(builder.start())
    } catch (e: IOException) {
        logger.log(Level.WARNING, "process start failed: $name", e)
        Outcome.Failure(CodedError(ErrorCodes.PROCESS_START_FAILED, "Could not start '$name'"))
    } catch (e: IllegalArgumentException) {
        logger.log(Level.WARNING, "process start rejected: $name", e)
        Outcome.Failure(CodedError(ErrorCodes.PROCESS_START_FAILED, "Could not start '$name'"))
    }
}

/** Suspends until the process exits; cancelling the caller does not touch the process. */
internal suspend fun Process.awaitExit(): Int = suspendCancellableCoroutine { continuation ->
    onExit().whenComplete { process, error ->
        if (error != null) continuation.resumeWithException(error) else continuation.resume(process.exitValue())
    }
}

/**
 * Kills [process] and every descendant. Descendants are collected before the parent dies, because once it is gone
 * they are re-parented and can no longer be found. Closing the pipes afterwards releases any reader still blocked.
 */
internal fun terminateTree(process: Process, logger: Logger) {
    val handle = process.toHandle()
    repeat(2) { handle.descendants().forEach { it.destroyForcibly() } } // twice: catches a child spawned mid-kill
    process.destroyForcibly()
    try {
        process.waitFor(WAIT_AFTER_KILL_SECONDS, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
    closeQuietly(process.inputStream, logger)
    closeQuietly(process.errorStream, logger)
    closeQuietly(process.outputStream, logger)
}

internal fun closeQuietly(closeable: Closeable, logger: Logger) {
    try {
        closeable.close()
    } catch (e: IOException) {
        logger.log(Level.FINE, "close failed", e)
    }
}

private const val WAIT_AFTER_KILL_SECONDS = 2L
