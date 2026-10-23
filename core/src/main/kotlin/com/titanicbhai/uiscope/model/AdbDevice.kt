package com.titanicbhai.uiscope.model

/** The state column of `adb devices`. [NO_PERMISSIONS] is Linux's "no permissions (…)" (a missing udev rule), not a real adb state word. */
enum class DeviceState { DEVICE, OFFLINE, UNAUTHORIZED, AUTHORIZING, CONNECTING, RECOVERY, SIDELOAD, BOOTLOADER, HOST, NO_PERMISSIONS }

enum class Connection { USB, WIFI, EMULATOR, UNKNOWN }

/** One row of `adb devices -l`. Only a device in state [DeviceState.DEVICE] can be inspected. */
data class AdbDevice(
    val serial: String,
    val state: DeviceState,
    val model: String? = null,
    val product: String? = null,
    val transportId: String? = null,
    val connection: Connection = Connection.UNKNOWN,
) {
    val isUsable: Boolean get() = state == DeviceState.DEVICE
}
