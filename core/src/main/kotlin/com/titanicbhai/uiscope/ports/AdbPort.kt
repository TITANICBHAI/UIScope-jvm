package com.titanicbhai.uiscope.ports

import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.AdbDevice
import com.titanicbhai.uiscope.model.Capture
import kotlinx.coroutines.flow.Flow

/** Which adb is in use, for the status line and the Settings screen. */
data class AdbStatus(val path: String, val version: String, val source: String)

sealed interface TrackerEvent {
    data class Devices(val devices: List<AdbDevice>) : TrackerEvent

    data class Problem(val error: AppError) : TrackerEvent
}

/**
 * Everything the Android state holder needs from the outside world. The engine implements it; tests use a fake.
 * Implementations may block: the state holder always calls these off the main thread.
 */
interface AdbPort {
    /** Finds adb (or fails with `ADB_NOT_FOUND`). Called again after the user installs or chooses one. */
    suspend fun locate(): Outcome<AdbStatus>

    /** The device list now and whenever it changes, until the collector goes away. */
    fun trackDevices(): Flow<TrackerEvent>

    /** One atomic capture (tree + screenshot) of [device]. Cancelling the caller must stop it and kill any process it started. */
    suspend fun capture(device: AdbDevice): Outcome<Capture>
}
