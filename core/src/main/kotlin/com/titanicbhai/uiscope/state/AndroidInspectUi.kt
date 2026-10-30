package com.titanicbhai.uiscope.state

import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.model.AdbDevice
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.ports.AdbStatus
import java.time.Instant

/** The one visible state of the Android inspector (plan §4.3). [Starting] and [ChooseDevice] are additions: before adb is located, and several usable devices. */
sealed interface AndroidState {
    data object Starting : AndroidState

    data object AdbMissing : AndroidState

    data object NoDevice : AndroidState

    data class ChooseDevice(val devices: List<AdbDevice>) : AndroidState

    data class Unauthorized(val device: AdbDevice) : AndroidState

    data class Offline(val device: AdbDevice) : AndroidState

    data class NoPermissions(val device: AdbDevice) : AndroidState

    data class Ready(val device: AdbDevice) : AndroidState

    data class Capturing(val device: AdbDevice, val previous: Capture?) : AndroidState

    data class Captured(val device: AdbDevice, val capture: Capture) : AndroidState

    data class CaptureFailed(val device: AdbDevice, val error: AppError, val lastCapture: Capture?) : AndroidState

    /** The device went away; the last capture stays on screen ("Disconnected — showing last capture from 12:03:41"). */
    data class Disconnected(val serial: String, val lastCapture: Capture?, val since: Instant) : AndroidState
}

/** Why Live stopped on its own. The user resumes it with a button. */
sealed interface LivePause {
    data class Failures(val error: AppError) : LivePause

    data object DeviceDisconnected : LivePause
}

data class LiveState(val enabled: Boolean = false, val pause: LivePause? = null)

/** Everything the screen draws. Immutable; replaced as a whole on every change. */
data class AndroidInspectUi(
    val state: AndroidState = AndroidState.Starting,
    val adb: AdbStatus? = null,
    val devices: List<AdbDevice> = emptyList(),
    val selectedSerial: String? = null,
    /** The newest capture of the selected device (kept while a new one is taken or fails). */
    val capture: Capture? = null,
    /** Built once per capture, off the main thread. */
    val index: ElementIndex? = null,
    val selected: ElementPath? = null,
    val expanded: Set<ElementPath> = emptySet(),
    val live: LiveState = LiveState(),
    /** A problem with adb itself (for example the server cannot start), shown as a banner. */
    val problem: AppError? = null,
)

/** One-off happenings the UI turns into toasts or cues. */
sealed interface AndroidEvent {
    data object AlreadyCapturing : AndroidEvent

    data object SelectionLost : AndroidEvent

    data object Reconnected : AndroidEvent
}
