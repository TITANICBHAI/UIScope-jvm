package com.titanicbhai.uiscope.windows

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.process.ManagedProcess
import com.titanicbhai.uiscope.process.ProcessLauncher
import java.util.concurrent.ConcurrentHashMap
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

/**
 * The client side of the helper protocol (plan P3-2, Appendix J). Owns the helper process: spawns it, performs the
 * `hello` handshake (refusing a different major protocol version), correlates responses to requests by id, delivers
 * events in order, and enforces per-request timeouts. A timed-out helper is killed and restarted - at most three
 * restarts per minute, then `UIA_UNAVAILABLE` until the client is stopped. Malformed stdout lines are ignored and
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
    /** Events from the helper (pickHover, hotkey, ...) in arrival order. Collect once. */
    val events: SharedFlow<HelperLine.Event> = eventFlow

    private val eventFlow = MutableSharedFlow<HelperLine.Event>(extraBufferCapacity = 64)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<HelperLine.Response>>()
    private val idCounter = java.util.concurrent.atomic.AtomicLong(0)
    private val restartTimes = ArrayDeque<Long>()
    private val mutex = Mutex()
    private var worker: Job? = null
    private var process: ManagedProcess? = null
    private var stopped = false

    /** Starts the helper process and performs the handshake. Safe to call once per client lifetime. */
    suspend fun start(scope: CoroutineScope): Outcome<Unit> = mutex.withLock {
        if (process != null) return Outcome.Success(Unit)
        stopped = false
        spawnAndHandshake(scope)
    }

    /**
     * Sends a request and awaits its response. On timeout the helper is killed and restarted (the timed-out request
     * fails); after three restarts within a minute the client gives up with `UIA_UNAVAILABLE`.
     */
    suspend fun request(type: String, params: Map<String, Any?> = emptyMap(), timeout: Duration = requestTimeout): Outcome<Map<String, Any?>> {
        val id = idCounter.incrementAndGet()
        repeat(2) { attempt ->
            val started = ensureStarted()
            if (started is Outcome.Failure) return started
            val deferred = CompletableDeferred<HelperLine.Response>()
            pending[id] = deferred
            val line = HelperProtocol.request(id, type, params)
            val written = mutex.withLock { process?.writeLine(line) }
            if (written is Outcome.Failure) {
                pending.remove(id)
                restartWithPolicy() ?: return Failure(id, "the helper is not running (${written.errorOrAbort()})")
                continue
            }
            val response = try {
                withTimeout(timeout) { deferred.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                pending.remove(id)
                logger.log(Level.WARNING, "helper request $type timed out after $timeout; restarting the helper")
                restartWithPolicy() ?: return Failure(id, "the helper was restarted too often; giving up")
                return@repeat
            }
            if (!response.ok) {
                return Outcome.Failure(CodedError(response.errorCode ?: ErrorCodes.UNEXPECTED, response.errorMessage))
            }
            return Outcome.Success(response.value)
        }
        return Failure(id, "the helper could not be reached after a restart")
    }

    /** Sends `shutdown` and stops reading. The process tree is killed regardless. */
    suspend fun stop() {
        mutex.withLock {
            stopped = true
            try {
                withTimeout(SHUTDOWN_TIMEOUT) {
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

    private fun ensureStarted(): Outcome<Unit> = when {
        process != null -> Outcome.Success(Unit)
        else -> spawnAndHandshake(clientScope)
    }

    private suspend fun spawnAndHandshake(scope: CoroutineScope): Outcome<Unit> {
        val started = launcher.start(helperCommand, scope)
        val managed = when (started) {
            is Outcome.Failure -> return started
            is Outcome.Success -> started.value
        }
        process = managed
        worker = scope.launch { readLoop(managed) }
        return handshake()
    }

    private suspend fun readLoop(managed: ManagedProcess) {
        managed.stdoutLines.collect { line ->
            when (val decoded = HelperProtocol.decode(line)) {
                is HelperLine.Response -> pending.remove(decoded.id)?.complete(decoded)
                    ?: logger.log(Level.FINE, "a response for an unknown id arrived and was dropped")
                is HelperLine.Event -> eventFlow.emit(decoded)
                HelperLine.Malformed -> logger.log(Level.WARNING, "the helper wrote a malformed protocol line; it was ignored")
            }
        }
    }

    private suspend fun handshake(): Outcome<Unit> {
        val response = withTimeoutOrNull(HANDSHAKE_TIMEOUT) {
            val id = idCounter.incrementAndGet()
            val deferred = CompletableDeferred<HelperLine.Response>()
            pending[id] = deferred
            mutex.withLock { process?.writeLine(HelperProtocol.request(id, "hello")) }
            deferred.await()
        } ?: return Outcome.Failure(protocolFailure("the helper did not answer the hello handshake"))
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
        restartTimes.removeAll { now - it > RESTART_WINDOW_MS }
        if (restartTimes.size >= MAX_RESTARTS_PER_MINUTE) {
            logger.log(Level.WARNING, "the helper restarted more than $MAX_RESTARTS_PER_MINUTE times in a minute; giving up")
            return null
        }
        restartTimes.addLast(now)
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

    private fun Failure(id: Long, detail: String): Outcome<Map<String, Any?>> =
        Outcome.Failure(CodedError(ErrorCodes.UIA_UNAVAILABLE, detail))

    private fun protocolFailure(detail: String): CodedError = CodedError(ErrorCodes.UIA_UNAVAILABLE, detail)

    private fun Outcome.Failure.errorOrAbort(): String = error.code

    private val clientScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    companion object {
        val DEFAULT_TIMEOUT: Duration = 5.seconds
        private val HANDSHAKE_TIMEOUT: Duration = 5.seconds
        private val SHUTDOWN_TIMEOUT: Duration = 1.seconds
        private const val RESTART_WINDOW_MS = 60_000
        private const val MAX_RESTARTS_PER_MINUTE = 3
    }
}
