package com.titanicbhai.uiscope.windows

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.process.ManagedProcess
import com.titanicbhai.uiscope.process.ProcessLauncher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The client side of the helper protocol (plan P3-2, Appendix J). Owns the helper process: spawns it, performs the
 * `hello` handshake (refusing a different major protocol version), correlates responses to requests by id, delivers
 * events in order, and enforces per-request timeouts. A timed-out helper is killed and restarted - at most three
 * restarts per minute, then `UIA_UNAVAILABLE` until the client is stopped. A helper that exits on its own fails the requests that were
 * waiting for it at once (no waiting out the timeout) and is started again by the next request. Malformed stdout lines are ignored and
 * logged; the helper's diagnostics go to stderr and never reach the protocol.
 */
class WindowsHelperClient(
    private val launcher: ProcessLauncher,
    private val helperCommand: List<String>,
    private val dispatchers: DispatcherProvider,
    private val requestTimeout: Duration = DEFAULT_TIMEOUT,
    private val protocolVersion: Int = HelperProtocol.PROTOCOL_VERSION,
    private val logger: Logger = Logger.getLogger("uiscope.windows"),
) {
    private val eventFlow = MutableSharedFlow<Line.Event>(extraBufferCapacity = 64)

    /** Events from the helper (pickHover, hotkey, ...) in arrival order. Collect once. */
    val events: SharedFlow<Line.Event> = eventFlow

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Line.Response>>()
    private val idCounter = AtomicLong(0)
    private val restartTimes = ArrayDeque<Long>()
    private val mutex = Mutex()
    private val clientScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private var worker: Job? = null
    @Volatile private var process: ManagedProcess? = null
    private var stopped = false

    // Wire diagnostics for the give-up error (never screen content: protocol lines only).
    @Volatile private var lastSent = ""
    @Volatile private var lastReceived = ""
    @Volatile private var receivedCount = 0L

    /** The helper's most recent stderr lines, for diagnostics when it misbehaves (never shown to the user). */
    private val stderrTail = ArrayDeque<String>()

    private fun watchStderr(managed: ManagedProcess) {
        clientScope.launch {
            managed.stderrLines.collect { line ->
                synchronized(stderrTail) {
                    stderrTail.addLast(line)
                    while (stderrTail.size > STDERR_LINES) stderrTail.removeFirst()
                }
            }
        }
    }

    private fun stderrSnapshot(): String = synchronized(stderrTail) { stderrTail.joinToString(" | ").take(STDERR_CHARS) }

    /**
     * One line for the log and "Copy details": which helper process is running, how much it said, how often it was restarted and what it
     * last wrote on stderr. Never contains screen content.
     */
    fun diagnostics(): String {
        val running = process
        return "helper pid=${running?.pid ?: "none"} alive=${running?.isAlive ?: false} messages=$receivedCount " +
            "restarts-in-last-minute=${synchronized(restartTimes) { restartTimes.size }} stderr=[${stderrSnapshot()}]"
    }

    /** Starts the helper process and performs the handshake. Idempotent. */
    suspend fun start(scope: CoroutineScope): Outcome<Unit> = mutex.withLock {
        if (process != null) return Outcome.Success(Unit)
        spawnAndHandshake(scope)
    }

    /**
     * Sends a request and awaits its response. On timeout the helper is killed and restarted (the timed-out request
     * fails); after three restarts within a minute the client gives up with `UIA_UNAVAILABLE`.
     */
    suspend fun request(type: String, params: Map<String, Any?> = emptyMap(), timeout: Duration = requestTimeout): Outcome<Map<String, Any?>> {
        val id = idCounter.incrementAndGet()
        var attempt = 0
        while (true) {
            val started = ensureStarted()
            if (started is Outcome.Failure) return started
            val deferred = CompletableDeferred<Line.Response>()
            pending[id] = deferred
            val written = mutex.withLock {
                lastSent = HelperProtocol.request(id, type, params).take(120)
                process?.writeLine(HelperProtocol.request(id, type, params))
            }
            if (written is Outcome.Failure) {
                pending.remove(id)
                if (restartWithPolicy() == null) return helperGaveUp("the helper is not running (${written.errorOrAbort()})")
                attempt++
                if (attempt >= 2) return helperGaveUp("the helper could not be reached after a restart")
                continue
            }
            var response: Line.Response? = null
            var timedOut = false
            try {
                response = withTimeout(timeout) { deferred.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                timedOut = true
            }
            if (timedOut) {
                pending.remove(id)
                logger.log(Level.WARNING, "helper request $type timed out after $timeout; restarting the helper")
                if (restartWithPolicy() == null) return helperGaveUp("the helper was restarted too often; giving up")
                attempt++
                if (attempt >= 2) return helperGaveUp("the helper could not be reached after a restart")
                continue
            }
            if (!response!!.ok) {
                if (response.errorCode == CODE_HELPER_EXITED) {
                    // The helper died while this request was waiting; the next loop pass starts a fresh one (once).
                    attempt++
                    if (attempt >= 2) return helperGaveUp(response.errorMessage ?: "the helper exited")
                    continue
                }
                return Outcome.Failure(CodedError(response.errorCode ?: ErrorCodes.UNEXPECTED, response.errorMessage))
            }
            return Outcome.Success(response.value)
        }
    }

    /** Sends `shutdown` and stops reading. The process tree is killed regardless. */
    suspend fun stop() {
        mutex.withLock {
            stopped = true
            try {
                withTimeoutOrNull(SHUTDOWN_TIMEOUT) {
                    process?.writeLine(HelperProtocol.request(idCounter.incrementAndGet(), "shutdown"))
                }
            } catch (e: Exception) {
                logger.log(Level.FINE, "the helper ignored shutdown", e)
            }
            process?.kill()
            worker?.cancelAndJoin()
            process = null
            worker = null
        }
    }

    private suspend fun ensureStarted(): Outcome<Unit> {
        if (process != null) return Outcome.Success(Unit)
        // Under the lock: two requests arriving together must not start two helpers.
        return mutex.withLock { if (process != null) Outcome.Success(Unit) else spawnAndHandshake(clientScope) }
    }

    private suspend fun spawnAndHandshake(scope: CoroutineScope): Outcome<Unit> {
        val started = launcher.start(helperCommand, scope)
        val managed = when (started) {
            is Outcome.Failure -> return started
            is Outcome.Success -> started.value
        }
        process = managed
        watchStderr(managed)
        worker?.cancelAndJoin()
        worker = scope.launch { readLoop(managed) }
        val result = handshake()
        if (result is Outcome.Failure) {
            // A helper that failed (or never answered) the handshake must not stay registered as "running": the next try starts a new one.
            logger.log(Level.WARNING, "the helper failed the handshake: ${result.error.detail}")
            managed.kill()
            if (process === managed) process = null
        }
        return result
    }

    private suspend fun readLoop(managed: ManagedProcess) {
        managed.stdoutLines.collect { line ->
            receivedCount++
            lastReceived = line.take(120)
            when (val decoded = HelperProtocol.decode(line)) {
                is Line.Response -> pending.remove(decoded.id)?.complete(decoded)
                    ?: logger.log(Level.FINE, "a response for an unknown id arrived and was dropped")
                is Line.Event -> eventFlow.emit(decoded)
                Line.Malformed -> logger.log(Level.WARNING, "the helper wrote a malformed protocol line; it was ignored")
            }
        }
        onProcessEnded(managed)
    }

    /**
     * stdout closed. If this is still the current helper it died on its own: forget it (the next request starts a new one) and fail
     * everything that was waiting for an answer right now instead of letting each request sit out its timeout. A helper that was
     * replaced or stopped on purpose has nothing pending that belongs to it.
     */
    private fun onProcessEnded(managed: ManagedProcess) {
        if (process !== managed || stopped) return
        process = null
        val detail = "the helper process exited unexpectedly; its stderr: [${stderrSnapshot()}]"
        logger.log(Level.WARNING, detail)
        for (id in pending.keys.toList()) {
            pending.remove(id)?.complete(Line.Response(id, false, emptyMap(), CODE_HELPER_EXITED, detail))
        }
    }

    private suspend fun handshake(): Outcome<Unit> {
        // No client mutex here: start()/restartWithPolicy() already hold it, and Kotlin's Mutex is not reentrant.
        // writeLine is internally synchronized on the process's own stdin lock.
        val response = withTimeoutOrNull(HANDSHAKE_TIMEOUT) {
            val id = idCounter.incrementAndGet()
            val deferred = CompletableDeferred<Line.Response>()
            pending[id] = deferred
            process?.writeLine(HelperProtocol.request(id, "hello"))
            deferred.await()
        } ?: return Outcome.Failure(
            protocolFailure("the helper did not answer the hello handshake; its stderr: [${stderrSnapshot()}]"),
        )
        if (!response.ok) {
            return Outcome.Failure(protocolFailure("the helper refused the handshake (${response.errorMessage ?: "unknown"})"))
        }
        val remote = response.value["protocol"] as? Long
        if (remote != protocolVersion.toLong()) {
            process?.kill()
            process = null
            return Outcome.Failure(protocolFailure("the helper speaks protocol $remote, this build expects $protocolVersion"))
        }
        return Outcome.Success(Unit)
    }

    /** Restarts the helper unless it restarted too often; null means give up (the caller fails its request). */
    private suspend fun restartWithPolicy(): ManagedProcess? = mutex.withLock {
        if (stopped) return null
        val now = System.currentTimeMillis()
        val tooMany = synchronized(restartTimes) {
            restartTimes.removeAll { now - it > RESTART_WINDOW_MS }
            if (restartTimes.size >= MAX_RESTARTS_PER_MINUTE) true else { restartTimes.addLast(now); false }
        }
        if (tooMany) {
            logger.log(Level.WARNING, "the helper restarted more than $MAX_RESTARTS_PER_MINUTE times in a minute; giving up")
            return null
        }
        process?.kill()
        process = null
        val started = launcher.start(helperCommand, clientScope)
        val managed = when (started) {
            is Outcome.Failure -> {
                logger.log(Level.WARNING, "the helper could not be restarted: ${started.error.code}")
                return null
            }
            is Outcome.Success -> started.value
        }
        process = managed
        watchStderr(managed)
        worker?.cancelAndJoin()
        worker = clientScope.launch { readLoop(managed) }
        val handshake = handshake()
        if (handshake is Outcome.Failure) {
            logger.log(Level.WARNING, "the restarted helper failed the handshake: ${handshake.error.detail}")
            process?.kill()
            process = null
            return null
        }
        managed
    }

    private fun helperGaveUp(detail: String): Outcome<Map<String, Any?>> =
        Outcome.Failure(
            CodedError(
                ErrorCodes.UIA_UNAVAILABLE,
                "$detail [sent=${lastSent.take(80)} received=$receivedCount last-recv=${lastReceived.take(80)}]",
            ),
        )

    private fun protocolFailure(detail: String): CodedError = CodedError(ErrorCodes.UIA_UNAVAILABLE, detail)

    private fun Outcome.Failure.errorOrAbort(): String = error.code

    companion object {
        val DEFAULT_TIMEOUT: Duration = 5.seconds

        /** Walking a big window (thousands of elements, a busy browser) legitimately takes many seconds; the helper has its own, shorter, time budget. */
        val CAPTURE_TIMEOUT: Duration = 90.seconds

        /** Enumerating windows asks Windows about every process once; a machine under load needs more than a plain request. */
        val LIST_TIMEOUT: Duration = 20.seconds

        /** Not a helper code: the client's own marker for "the process ended while this request was waiting". */
        const val CODE_HELPER_EXITED = "HELPER_EXITED"
        private const val STDERR_LINES = 8
        private const val STDERR_CHARS = 700
        // A cold JVM boot on a loaded CI runner can take a while; the handshake waits longer than a request.
        private val HANDSHAKE_TIMEOUT: Duration = 20.seconds
        private val SHUTDOWN_TIMEOUT: Duration = 1.seconds
        private const val RESTART_WINDOW_MS = 60_000
        private const val MAX_RESTARTS_PER_MINUTE = 3
    }
}
