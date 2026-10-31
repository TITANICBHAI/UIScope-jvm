package com.titanicbhai.uiscope.state

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.ports.PickEvent
import com.titanicbhai.uiscope.ports.WindowsInspectorPort
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Windows inspector's state holder (plan P3-8, 4.4). It talks to the outside only through [WindowsInspectorPort];
 * every call that can block runs on `dispatchers.io`, and [scope] (normally the main dispatcher) only ever changes
 * state. Not thread-affine: all state changes are atomic replacements of [ui].
 *
 * Rules implemented here: the helper is self-tested at start (listWindows); single-flight capture; pick mode with the
 * picked element relocated in the captured tree (RuntimeId, then AutomationId, then rect); refresh by handle that keeps
 * the last capture when the window is gone; selection restored by identity after every capture.
 */
class WindowsInspectState(
    private val port: WindowsInspectorPort,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
) {
    private val logger = Logger.getLogger("uiscope.state.windows")

    private val state = MutableStateFlow(WindowsInspectUi())
    val ui: StateFlow<WindowsInspectUi> = state.asStateFlow()

    private val eventFlow = MutableSharedFlow<WindowsEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<WindowsEvent> = eventFlow.asSharedFlow()

    private val capturing = AtomicBoolean(false)

    @Volatile private var startJob: Job? = null

    @Volatile private var captureJob: Job? = null

    @Volatile private var pickJob: Job? = null

    private fun mutate(change: WindowsInspectUi.() -> WindowsInspectUi) {
        while (true) {
            val current = state.value
            if (state.compareAndSet(current, current.change())) return
        }
    }

    // ── start / stop ───────────────────────────────────────────────────────────────────────────

    /** Checks the helper (listWindows is the cheap self-test) and loads the "Choose window..." list. */
    fun start() {
        startJob?.cancel()
        startJob = scope.launch {
            mutate { copy(state = WindowsState.Starting, problem = null) }
            when (val windows = withContext(dispatchers.io) { port.listWindows() }) {
                is Outcome.Failure -> mutate {
                    copy(state = WindowsState.HelperMissing(windows.error), windows = emptyList(), problem = windows.error)
                }
                is Outcome.Success -> mutate {
                    copy(state = WindowsState.Idle, windows = windows.value, problem = null)
                }
            }
            // The hotkey event can arrive at any time (plan P3-6): it starts a pick when idle.
            port.pickEvents().collect { event ->
                if (event is PickEvent.HotkeyPressed && !capturing.get() && !state.value.picking) {
                    startPick()
                }
            }
        }
    }

    /** Cancels a running capture or pick and stops listening. */
    fun stop() {
        captureJob?.cancel()
        pickJob?.cancel()
        if (ui.value.picking) {
            scope.launch { withContext(dispatchers.io) { port.stopPick() } }
        }
    }

    // ── capture ────────────────────────────────────────────────────────────────────────────────

    /** Captures [hwnd] (from "Choose window..." or a pick). Single-flight: a running capture ignores new requests. */
    fun chooseWindow(hwnd: String) {
        startCapture(hwnd)
    }

    /** Re-captures the current window (F5). If it is gone, the last capture stays and the UI says so. */
    fun refresh() {
        val hwnd = state.value.selectedHwnd ?: return
        startCapture(hwnd, isRefresh = true)
    }

    private fun startCapture(hwnd: String, isRefresh: Boolean = false) {
        if (!capturing.compareAndSet(false, true)) {
            logger.fine("a capture is already running; ignoring the request for $hwnd")
            return
        }
        val previous = state.value.capture
        val window = state.value.windows.firstOrNull { it.hwnd == hwnd }
        mutate {
            copy(
                state = WindowsState.Capturing(window ?: fakeWindow(hwnd), previous = previous),
                selectedHwnd = hwnd,
                picking = false,
                hoverLabel = null,
            )
        }
        captureJob = scope.launch {
            val result = withContext(dispatchers.io) { port.captureWindow(hwnd) }
            when (result) {
                is Outcome.Failure -> {
                    capturing.set(false)
                    if (result.error.code == ErrorCodes.WINDOW_GONE) {
                        mutate { copy(state = WindowsState.WindowGone(lastCapture = previous)) }
                    } else {
                        mutate {
                            copy(
                                state = WindowsState.CaptureFailed(
                                    window ?: fakeWindow(hwnd), result.error, lastCapture = previous,
                                ),
                            )
                        }
                    }
                }
                is Outcome.Success -> {
                    val capture = result.value
                    val index = withContext(dispatchers.default) { ElementIndex(capture) }
                    capturing.set(false)
                    val picked = pendingPickElement
                    pendingPickElement = null
                    val previousIndex = state.value.index
                    val previousSelected = state.value.selected
                    val pickedPath = picked?.let { relocatePicked(index, it) }
                    val restored = pickedPath
                        ?: previousSelected?.let { path ->
                            previousIndex?.byPath?.get(path)?.let { node -> index.relocate(previousIndex.identityOf(node))?.path }
                        }
                    val keptSelected: ElementPath? = previousSelected?.takeIf { index.byPath.containsKey(it) }
                    mutate {
                        copy(
                            state = WindowsState.Captured(window ?: fakeWindow(hwnd), capture),
                            capture = capture,
                            index = index,
                            selected = restored ?: keptSelected,
                            expanded = expanded.filter { index.byPath.containsKey(it) }.toSet(),
                        )
                    }
                    if (restored == null && previousSelected != null) eventFlow.emit(WindowsEvent.SelectionLost)
                }
            }
        }
    }

    /** Cancels the running capture (the port call is cancelled, which kills the helper's work for it). */
    fun cancelCapture() {
        captureJob?.cancel()
        if (capturing.compareAndSet(true, false)) {
            val previous = state.value.capture
            val hwnd = state.value.selectedHwnd
            mutate {
                copy(
                    state = if (previous != null && hwnd != null) {
                        WindowsState.Captured(fakeWindow(hwnd), previous)
                    } else {
                        WindowsState.Idle
                    },
                )
            }
        }
    }

    // ── pick mode ──────────────────────────────────────────────────────────────────────────────

    /** Starts pick mode: the helper's overlay comes up. The pick result captures the window and selects the element. */
    fun startPick() {
        if (capturing.get()) return
        pickJob?.cancel()
        mutate { copy(picking = true, hoverLabel = null, state = WindowsState.Picking) }
        pickJob = scope.launch {
            when (val started = withContext(dispatchers.io) { port.startPick() }) {
                is Outcome.Failure -> {
                    mutate { copy(picking = false, problem = started.error, state = WindowsState.Idle) }
                    return@launch
                }
                is Outcome.Success -> Unit
            }
            port.pickEvents().collect { event ->
                when (event) {
                    is PickEvent.Hover -> mutate { copy(hoverLabel = event.label) }
                    is PickEvent.Result -> {
                        withContext(dispatchers.io) { port.stopPick() }
                        mutate { copy(picking = false, hoverLabel = null) }
                        pendingPickElement = event.element
                        chooseWindow(event.hwnd)
                    }
                    PickEvent.Cancelled -> {
                        mutate { copy(picking = false, hoverLabel = null, state = WindowsState.Idle) }
                        eventFlow.emit(WindowsEvent.PickCancelled)
                    }
                }
            }
        }
    }

    /** Leaves pick mode (Esc / right-click). */
    fun cancelPick() {
        pickJob?.cancel()
        scope.launch {
            withContext(dispatchers.io) { port.stopPick() }
            mutate { copy(picking = false, hoverLabel = null, state = WindowsState.Idle) }
        }
    }

    /** Registers (or removes) the pick hotkey. The failure carries HOTKEY_IN_USE when the combination is taken (P3-6). */
    suspend fun setHotkey(enabled: Boolean, modifiers: String, key: String): Outcome<Unit> =
        withContext(dispatchers.io) {
            if (enabled) port.registerHotkey(modifiers, key) else port.unregisterHotkey()
        }

    @Volatile private var pendingPickElement: com.titanicbhai.uiscope.ports.PickedElement? = null

    // ── selection and expansion ────────────────────────────────────────────────────────────────

    fun select(path: ElementPath?) = mutate { copy(selected = path?.takeIf { index?.byPath?.containsKey(it) == true }) }

    fun toggleExpanded(path: ElementPath) = mutate { copy(expanded = if (path in expanded) expanded - path else expanded + path) }

    fun expandAll() = mutate {
        copy(expanded = index?.nodes?.filter { it.children.isNotEmpty() }?.map { it.path }?.toSet() ?: expanded)
    }

    fun collapseAll() = mutate { copy(expanded = emptySet()) }

    fun expandSubtree(path: ElementPath) = mutate {
        val subtree = index?.byPath?.get(path)?.let(::subtreeContainers) ?: return@mutate copy()
        copy(expanded = expanded + subtree)
    }

    fun collapseSubtree(path: ElementPath) = mutate {
        val subtree = index?.byPath?.get(path)?.let(::subtreeContainers) ?: return@mutate copy()
        copy(expanded = expanded - subtree)
    }

    private fun subtreeContainers(root: ElementNode): Set<ElementPath> {
        val out = HashSet<ElementPath>()
        val pending = ArrayDeque<ElementNode>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (node.children.isNotEmpty()) out += node.path
            node.children.forEach { pending.addLast(it) }
        }
        return out
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    /** The window list may not know a just-picked hwnd; the status line still needs a name. */
    private fun fakeWindow(hwnd: String) = com.titanicbhai.uiscope.ports.WindowInfo(hwnd, "", "", 0, false)

    /**
     * Relocates the picked element inside the freshly captured tree (Appendix J): by RuntimeId property first, then
     * AutomationId, then the smallest node whose bounds contain the picked rectangle.
     */
    private fun relocatePicked(index: ElementIndex, picked: com.titanicbhai.uiscope.ports.PickedElement): ElementPath? {
        // A null criterion must never match nodes that merely LACK the property (that would pick the root).
        picked.runtimeId?.takeIf { it.isNotBlank() }?.let { runtimeId ->
            index.nodes.firstOrNull { it.properties["RuntimeId"] == runtimeId }?.let { return it.path }
        }
        picked.automationId?.takeIf { it.isNotBlank() }?.let { automationId ->
            index.nodes.firstOrNull { it.identifier == automationId }?.let { return it.path }
        }
        val center = picked.rect.let { it.x + it.width / 2.0 to it.y + it.height / 2.0 }
        return index.nodes
            .filter { it.bounds.contains(center.first, center.second) }
            .minByOrNull { it.bounds.area }
            ?.path
    }
}
