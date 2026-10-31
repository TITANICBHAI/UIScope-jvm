package com.titanicbhai.uiscope.state

import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.ports.WindowInfo

sealed interface WindowsState {
    /** The helper is being checked (listWindows is the cheap self-test). */
    data object Starting : WindowsState

    /** The helper cannot start or speaks the wrong protocol: Windows inspection is unavailable. */
    data class HelperMissing(val error: AppError) : WindowsState

    /** The helper is ready; no window captured yet. */
    data object Idle : WindowsState

    /** Pick mode: the overlay is up and the mouse hook swallows the pick click. */
    data object Picking : WindowsState

    data class Capturing(val window: WindowInfo, val previous: Capture?) : WindowsState

    data class Captured(val window: WindowInfo, val capture: Capture) : WindowsState

    data class CaptureFailed(val window: WindowInfo, val error: AppError, val lastCapture: Capture?) : WindowsState

    /** The picked window closed before a refresh could re-capture it; the last capture stays on screen. */
    data class WindowGone(val lastCapture: Capture?) : WindowsState
}

/** Everything the Windows Inspect screen draws. Immutable; replaced as a whole on every change. */
data class WindowsInspectUi(
    val state: WindowsState = WindowsState.Starting,
    /** The visible top-level windows, for "Choose window...". */
    val windows: List<WindowInfo> = emptyList(),
    val selectedHwnd: String? = null,
    val capture: Capture? = null,
    val index: ElementIndex? = null,
    val selected: ElementPath? = null,
    val expanded: Set<ElementPath> = emptySet(),
    /** True while pick mode is up. */
    val picking: Boolean = false,
    /** The overlay's current label ("Button - OK") while picking. */
    val hoverLabel: String? = null,
    /** A helper-level problem (for example the helper could not start), shown as a banner. */
    val problem: AppError? = null,
)

/** One-off happenings the UI turns into toasts or cues. */
sealed interface WindowsEvent {
    data object PickCancelled : WindowsEvent

    /** The selected element no longer exists after a refresh. */
    data object SelectionLost : WindowsEvent
}
