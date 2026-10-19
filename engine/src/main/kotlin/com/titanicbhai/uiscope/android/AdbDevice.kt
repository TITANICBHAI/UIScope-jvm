package com.titanicbhai.uiscope.android

data class AdbDevice(
    val serial: String,
    val state: AdbDeviceState,
    val model: String? = null,
    val androidVersion: String? = null,
    val displayName: String = model ?: serial
)

enum class AdbDeviceState {
    DEVICE,
    OFFLINE,
    UNAUTHORIZED,
    UNKNOWN;

    val label: String get() = when (this) {
        DEVICE -> "Connected"
        OFFLINE -> "Offline"
        UNAUTHORIZED -> "Unauthorized — tap Allow on device"
        UNKNOWN -> "Unknown"
    }

    val isUsable: Boolean get() = this == DEVICE
}
