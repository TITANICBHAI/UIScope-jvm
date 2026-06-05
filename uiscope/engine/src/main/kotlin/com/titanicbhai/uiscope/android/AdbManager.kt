package com.titanicbhai.uiscope.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AdbManager(private var adbPath: String = "adb") {

    fun setAdbPath(path: String) {
        adbPath = path.ifBlank { "adb" }
    }

    suspend fun listDevices(): List<AdbDevice> = withContext(Dispatchers.IO) {
        try {
            val output = runAdb("devices", "-l")
            parseDeviceList(output)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun connectDevice(address: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val output = runAdb("connect", address.trim())
            output.contains("connected") || output.contains("already connected")
        } catch (_: Exception) {
            false
        }
    }

    suspend fun disconnectDevice(serial: String): Boolean = withContext(Dispatchers.IO) {
        try {
            runAdb("disconnect", serial)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isAdbAvailable(): Boolean = try {
        val process = ProcessBuilder(adbPath, "version")
            .redirectErrorStream(true)
            .start()
        process.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun runAdb(vararg args: String): String {
        val process = ProcessBuilder(listOf(adbPath) + args.toList())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    private fun parseDeviceList(output: String): List<AdbDevice> {
        return output.lines()
            .drop(1)
            .filter { it.isNotBlank() && !it.startsWith("*") && !it.startsWith("List") }
            .mapNotNull { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size < 2) return@mapNotNull null
                val serial = parts[0]
                val stateStr = parts[1]
                val state = when (stateStr) {
                    "device" -> AdbDeviceState.DEVICE
                    "offline" -> AdbDeviceState.OFFLINE
                    "unauthorized" -> AdbDeviceState.UNAUTHORIZED
                    else -> AdbDeviceState.UNKNOWN
                }
                val model = parts.find { it.startsWith("model:") }
                    ?.removePrefix("model:")
                    ?.replace('_', ' ')
                AdbDevice(serial = serial, state = state, model = model)
            }
    }
}
