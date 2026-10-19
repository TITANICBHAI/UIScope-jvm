package com.titanicbhai.uiscope.testing

import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.error.Outcome
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.fail

/** Runs a suspending test body on the calling thread, failing (instead of hanging) after [timeoutMs]. */
fun blocking(timeoutMs: Long = 90_000, block: suspend CoroutineScope.() -> Unit) {
    runBlocking { withTimeout(timeoutMs) { block() } }
}

/** Creates a temporary directory for the duration of [block] and deletes it afterwards. */
inline fun <T> withTempDir(block: (Path) -> T): T {
    val dir = Files.createTempDirectory("uiscope-test")
    try {
        return block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

/** Polls [condition] every 25 ms until it holds, failing with [message] after [timeoutMs]. */
fun awaitTrue(timeoutMs: Long = 10_000, message: String = "condition", condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(25)
    }
    if (!condition()) fail("Timed out after $timeoutMs ms waiting for: $message")
}

/** The value of a successful [Outcome]; fails the test otherwise. */
fun <T> Outcome<T>.assertSuccess(): T = (this as? Outcome.Success<T>)?.value ?: fail("Expected Success but was $this")

/** The error of a failed [Outcome]; fails the test if it succeeded or if [code] is given and differs. */
fun Outcome<*>.assertFailure(code: String? = null): AppError {
    val failure = this as? Outcome.Failure ?: fail("Expected Failure but was $this")
    if (code != null && failure.error.code != code) fail("Expected error code $code but was ${failure.error}")
    return failure.error
}
