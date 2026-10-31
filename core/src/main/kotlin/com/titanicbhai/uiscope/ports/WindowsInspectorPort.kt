package com.titanicbhai.uiscope.ports

import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.Capture
import kotlinx.coroutines.flow.Flow

/** One visible top-level window (for "Choose window..."). [hwnd] is the hex handle string ("0x00120A3C", Appendix J). */
data class WindowInfo(
    val hwnd: String,
    val title: String,
    val processName: String,
    val pid: Long,
    val isElevated: Boolean,
)

/** What the user pointed at during pick mode (Appendix J's pickResult element). */
data class PickedElement(
    val runtimeId: String?,
    val automationId: String?,
    val name: String?,
    val controlType: String?,
    val rect: Bounds,
)

sealed interface PickEvent {
    /** The element currently under the cursor (the overlay's outline and label). */
    data class Hover(val label: String, val element: PickedElement) : PickEvent

    /** The user picked: [hwnd] is the top-level window to capture. */
    data class Result(val hwnd: String, val element: PickedElement) : PickEvent

    data object Cancelled : PickEvent
}

/**
 * Everything the Windows inspector's state holder needs from the outside (plan P3-8, 4.4). The engine implements it on
 * top of the helper client (P3-2); tests use a fake. Implementations may block: the state holder calls them off the main
 * thread. Failures carry Appendix A codes (UIA_UNAVAILABLE, WINDOW_GONE, ELEVATED_TARGET, ...).
 */
interface WindowsInspectorPort {
    /** The visible top-level windows, for "Choose window...". */
    suspend fun listWindows(): Outcome<List<WindowInfo>>

    /** Captures one top-level window (cached UIA subtree + screenshot at physical resolution). */
    suspend fun captureWindow(hwnd: String): Outcome<Capture>

    /** Starts pick mode: the overlay comes up, [pickEvents] delivers hover updates and the result. */
    suspend fun startPick(hideFromCapture: Boolean = true): Outcome<Unit>

    /** Removes the overlay and the mouse hook. */
    suspend fun stopPick(): Outcome<Unit>

    /** Pick events until pick mode ends. Single consumer: the state holder. */
    fun pickEvents(): Flow<PickEvent>
}
