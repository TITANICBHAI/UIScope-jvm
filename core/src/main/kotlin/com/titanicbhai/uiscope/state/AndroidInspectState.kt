package com.titanicbhai.uiscope.state

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.error.UnexpectedError
import com.titanicbhai.uiscope.model.AdbDevice
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.DeviceState
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.ports.AdbPort
import com.titanicbhai.uiscope.ports.TrackerEvent
import com.titanicbhai.uiscope.settings.SettingsStore
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Android inspector's state holder (plan §4.3, P2-5). It talks to the outside only through [AdbPort] and [SettingsStore]; every
 * call that can block runs on `dispatchers.io`, the index is built on `dispatchers.default`, and [scope] (normally the main
 * dispatcher) only ever changes state. Not thread-affine: all state changes are atomic replacements of [ui].
 *
 * Rules implemented here: device tracking with auto-select and "remember last serial"; one automatic capture when a device first becomes
 * Ready; single-flight capture; cancel; Live (interval from settings, paused while hidden, stops after 3 consecutive failures, never
 * saves anything); Disconnected/Reconnected; selection and expansion restored by element identity after every capture.
 */
class AndroidInspectState(
    private val adb: AdbPort,
    private val settings: SettingsStore,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    private val state = MutableStateFlow(AndroidInspectUi())
    val ui: StateFlow<AndroidInspectUi> = state.asStateFlow()

    private val eventFlow = MutableSharedFlow<AndroidEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AndroidEvent> = eventFlow.asSharedFlow()

    private val visible = MutableStateFlow(true)
    private val capturing = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val liveFailures = AtomicInteger(0)

    @Volatile private var startJob: Job? = null

    @Volatile private var trackJob: Job? = null

    @Volatile private var captureJob: Job? = null

    @Volatile private var liveJob: Job? = null

    private fun mutate(change: AndroidInspectUi.() -> AndroidInspectUi) {
        while (true) {
            val current = state.value
            if (state.compareAndSet(current, current.change())) return
        }
    }

    // ── start, stop, adb ───────────────────────────────────────────────────────────────────────

    /** Finds adb and starts watching for devices. Calling it again restarts both (use it after the user installs or chooses adb). */
    fun start() {
        startJob?.cancel()
        trackJob?.cancel()
        startJob = scope.launch {
            mutate { copy(state = AndroidState.Starting, problem = null) }
            when (val located = withContext(dispatchers.io) { adb.locate() }) {
                is Outcome.Failure -> mutate { copy(state = AndroidState.AdbMissing, adb = null, problem = located.error) }
                is Outcome.Success -> {
                    mutate { copy(adb = located.value, problem = null, state = AndroidState.NoDevice) }
                    trackJob = adb.trackDevices().flowOn(dispatchers.io).onEach { onTracker(it) }.launchIn(scope)
                }
            }
        }
    }

    fun stop() {
        startJob?.cancel()
        trackJob?.cancel()
        liveJob?.cancel()
        cancelCapture()
    }

    // ── devices ────────────────────────────────────────────────────────────────────────────────

    private fun onTracker(event: TrackerEvent) {
        when (event) {
            is TrackerEvent.Problem -> mutate { copy(problem = event.error) }
            is TrackerEvent.Devices -> {
                mutate { copy(problem = null, devices = event.devices) }
                reconcile(event.devices)
            }
        }
    }

    private fun reconcile(devices: List<AdbDevice>) {
        val selectedSerial = state.value.selectedSerial
        if (selectedSerial != null) {
            reconcileSelected(selectedSerial, devices.firstOrNull { it.serial == selectedSerial })
            return
        }
        val usable = devices.filter { it.isUsable }
        when {
            devices.isEmpty() -> mutate { copy(state = AndroidState.NoDevice) }
            usable.size == 1 -> choose(usable.single())
            usable.size > 1 -> {
                val remembered = usable.firstOrNull { it.serial == settings.settings.value.lastSerial }
                if (remembered != null) choose(remembered) else mutate { copy(state = AndroidState.ChooseDevice(usable)) }
            }
            else -> mutate { copy(state = unusable(devices.first())) }
        }
    }

    private fun reconcileSelected(serial: String, device: AdbDevice?) {
        val current = state.value
        if (device == null) {
            if (current.state !is AndroidState.Disconnected) disconnect(serial)
            return
        }
        if (!device.isUsable) {
            cancelCapture()
            mutate { copy(state = unusable(device)) }
            return
        }
        when (current.state) {
            is AndroidState.Disconnected -> {
                eventFlow.tryEmit(AndroidEvent.Reconnected)
                val last = current.capture
                mutate { copy(state = if (last != null) AndroidState.Captured(device, last) else AndroidState.Ready(device)) }
            }
            is AndroidState.Unauthorized, is AndroidState.Offline, is AndroidState.NoPermissions -> becomeReady(device)
            is AndroidState.Capturing -> mutate { copy(state = AndroidState.Capturing(device, current.capture)) }
            else -> Unit
        }
    }

    private fun unusable(device: AdbDevice): AndroidState = when (device.state) {
        DeviceState.UNAUTHORIZED -> AndroidState.Unauthorized(device)
        DeviceState.NO_PERMISSIONS -> AndroidState.NoPermissions(device)
        else -> AndroidState.Offline(device)
    }

    private fun disconnect(serial: String) {
        cancelCapture()
        liveJob?.cancel()
        val live = state.value.live
        mutate {
            copy(
                state = AndroidState.Disconnected(serial, capture, clock.instant()),
                live = if (live.enabled) live.copy(pause = LivePause.DeviceDisconnected) else live,
            )
        }
    }

    /** The user (or the single-device rule) picked [device]. Another device's capture is dropped; the choice is remembered. */
    fun selectDevice(serial: String) {
        val device = state.value.devices.firstOrNull { it.serial == serial && it.isUsable } ?: return
        choose(device)
    }

    private fun choose(device: AdbDevice) {
        if (state.value.selectedSerial != device.serial) {
            cancelCapture()
            mutate { copy(selectedSerial = device.serial, capture = null, index = null, selected = null, expanded = emptySet()) }
            settings.update { it.copy(lastSerial = device.serial) }
        }
        becomeReady(device)
    }

    private fun becomeReady(device: AdbDevice) {
        val last = state.value.capture
        mutate { copy(state = AndroidState.Ready(device)) }
        if (last == null) startCapture(fromLive = false) // one capture runs by itself so the screen is never empty
        else mutate { copy(state = AndroidState.Captured(device, last)) }
    }

    // ── capture ────────────────────────────────────────────────────────────────────────────────

    /** Capture button / F5. Returns false when nothing was started (no usable device, or one is already running: then the UI shows a cue). */
    fun capture(): Boolean = startCapture(fromLive = false) != null

    private fun usableDevice(): AdbDevice? = when (val s = state.value.state) {
        is AndroidState.Ready -> s.device
        is AndroidState.Captured -> s.device
        is AndroidState.CaptureFailed -> s.device
        is AndroidState.Capturing -> s.device
        else -> null
    }

    private fun startCapture(fromLive: Boolean): Job? {
        val device = usableDevice() ?: return null
        if (!capturing.compareAndSet(false, true)) {
            if (!fromLive) eventFlow.tryEmit(AndroidEvent.AlreadyCapturing)
            return if (fromLive) captureJob else null
        }
        val gen = generation.incrementAndGet()
        mutate { copy(state = AndroidState.Capturing(device, capture)) }
        val job = scope.launch {
            try {
                val outcome = try {
                    withContext(dispatchers.io) { adb.capture(device) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    Outcome.Failure(UnexpectedError(e))
                }
                when (outcome) {
                    is Outcome.Failure -> if (generation.get() == gen) {
                        mutate { copy(state = AndroidState.CaptureFailed(device, outcome.error, capture)) }
                    }
                    is Outcome.Success -> {
                        val index = withContext(dispatchers.default) { ElementIndex(outcome.value) }
                        if (generation.get() == gen) publish(device, outcome.value, index)
                    }
                }
            } finally {
                if (generation.get() == gen) capturing.set(false)
            }
        }
        captureJob = job
        return job
    }

    /** Publishes tree and screenshot together and restores selection and expansion by identity. */
    private fun publish(device: AdbDevice, capture: Capture, index: ElementIndex) {
        val before = state.value
        val old = before.index
        fun relocate(path: ElementPath): ElementPath? =
            old?.byPath?.get(path)?.let { node -> index.relocate(old.identityOf(node))?.path }
        val selected = before.selected?.let(::relocate)
        val expanded = before.expanded.mapNotNull(::relocate).toSet()
        if (before.selected != null && selected == null) eventFlow.tryEmit(AndroidEvent.SelectionLost)
        mutate { copy(state = AndroidState.Captured(device, capture), capture = capture, index = index, selected = selected, expanded = expanded) }
    }

    /** Esc: cancels the running capture (and, through the port, the processes it started). The previous capture stays. */
    fun cancelCapture() {
        generation.incrementAndGet()
        captureJob?.cancel()
        capturing.set(false)
        val current = state.value
        val s = current.state
        if (s is AndroidState.Capturing) {
            mutate { copy(state = if (capture != null) AndroidState.Captured(s.device, capture) else AndroidState.Ready(s.device)) }
        }
    }

    // ── live ───────────────────────────────────────────────────────────────────────────────────

    /** Live on/off (Ctrl+L). Turning it on also clears an earlier pause (that is what Resume does). */
    fun setLive(enabled: Boolean) {
        liveJob?.cancel()
        liveFailures.set(0)
        if (!enabled) {
            mutate { copy(live = LiveState()) }
            return
        }
        mutate { copy(live = LiveState(enabled = true, pause = null)) }
        liveJob = scope.launch { liveLoop() }
    }

    fun resumeLive() = setLive(true)

    /** Live is paused while the window is minimized or another destination is open; it continues by itself afterwards. */
    fun setVisibility(minimized: Boolean, inspectOpen: Boolean) {
        visible.value = !minimized && inspectOpen
    }

    private suspend fun liveLoop() {
        while (scope.isActive) {
            visible.first { it }
            if (usableDevice() != null) {
                startCapture(fromLive = true)?.join()
                when (val s = state.value.state) {
                    is AndroidState.CaptureFailed -> if (liveFailures.incrementAndGet() >= MAX_LIVE_FAILURES) {
                        mutate { copy(live = LiveState(enabled = true, pause = LivePause.Failures(s.error))) }
                        return
                    }
                    is AndroidState.Captured -> liveFailures.set(0)
                    else -> Unit
                }
            }
            delayFn(settings.settings.value.liveIntervalSeconds * MILLIS)
        }
    }

    // ── selection ──────────────────────────────────────────────────────────────────────────────

    fun select(path: ElementPath?) = mutate { copy(selected = path?.takeIf { index?.byPath?.containsKey(it) == true }) }

    fun toggleExpanded(path: ElementPath) = mutate { copy(expanded = if (path in expanded) expanded - path else expanded + path) }

    /** Expands every node that has children (the tree pane's Expand-all, P4-4). */
    fun expandAll() = mutate {
        copy(expanded = index?.nodes?.filter { it.children.isNotEmpty() }?.map { it.path }?.toSet() ?: expanded)
    }

    fun collapseAll() = mutate { copy(expanded = emptySet()) }

    fun expandSubtree(path: ElementPath) = mutate {
        val subtree = index?.byPath?.get(path)?.let(::subtreePaths) ?: return@mutate copy()
        copy(expanded = expanded + subtree)
    }

    fun collapseSubtree(path: ElementPath) = mutate {
        val subtree = index?.byPath?.get(path)?.let(::subtreePaths) ?: return@mutate copy()
        copy(expanded = expanded - subtree)
    }

    private fun subtreePaths(root: ElementNode): Set<ElementPath> {
        val out = HashSet<ElementPath>()
        val pending = ArrayDeque<ElementNode>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            out += node.path
            node.children.forEach { pending.addLast(it) }
        }
        return out
    }

    companion object {
        const val MAX_LIVE_FAILURES = 3
        private const val MILLIS = 1_000L
    }
}
