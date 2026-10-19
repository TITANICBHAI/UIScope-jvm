package com.titanicbhai.uiscope.ios

enum class IosDeviceType { PHYSICAL, SIMULATOR }

data class IosDevice(
    val udid: String,
    val name: String,
    val model: String? = null,
    val osVersion: String? = null,
    val type: IosDeviceType = IosDeviceType.PHYSICAL
) {
    val displayName: String get() = name.ifBlank { model ?: udid.take(8) }
    val isSimulator: Boolean get() = type == IosDeviceType.SIMULATOR
}
