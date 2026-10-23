package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.model.AdbDevice
import com.titanicbhai.uiscope.model.Connection
import com.titanicbhai.uiscope.model.DeviceState

/** Things in `adb devices` output that are not devices but matter to the user. */
sealed interface AdbSignal {
    data class VersionMismatch(val serverVersion: Int, val clientVersion: Int) : AdbSignal
}

data class DeviceListing(val devices: List<AdbDevice>, val signals: List<AdbSignal>)

data class AdbVersion(val protocol: String, val build: String?) {
    override fun toString(): String = if (build == null) protocol else "$protocol ($build)"
}

object AdbParsers {
    private val state = "device|offline|unauthorized|authorizing|connecting|recovery|sideload|bootloader|host|no permissions"
    private val deviceLine = Regex("""^(\S+)\s+($state)\b(.*)$""")
    private val mismatch = Regex("""adb server version \((\d+)\) doesn't match this client \((\d+)\)""")
    private val wirelessMdns = Regex("""^adb-.+\._adb-tls-connect\._tcp\.?$""")
    private val hostPort = Regex("""^[^\s:]+:\d+$""")

    /**
     * Strict parser for `adb devices -l`: a line is a device only if it starts with a serial followed by a known state word. Daemon
     * start-up chatter, the header and the "server version doesn't match" warning are never devices (M3: the old code dropped one line blindly).
     */
    fun parseDevices(output: String): DeviceListing {
        val devices = ArrayList<AdbDevice>()
        val signals = ArrayList<AdbSignal>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            mismatch.find(line)?.let { signals += AdbSignal.VersionMismatch(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("List of devices")) continue
            val match = deviceLine.matchEntire(line) ?: continue
            val serial = match.groupValues[1]
            val rest = match.groupValues[3]
            val fields = rest.split(Regex("\\s+")).filter { ':' in it }.associate { it.substringBefore(':') to it.substringAfter(':') }
            devices += AdbDevice(
                serial = serial,
                state = stateOf(match.groupValues[2]),
                model = fields["model"],
                product = fields["product"],
                transportId = fields["transport_id"],
                connection = connectionOf(serial, rest),
            )
        }
        return DeviceListing(devices, signals)
    }

    private fun stateOf(word: String): DeviceState =
        if (word.startsWith("no permissions")) DeviceState.NO_PERMISSIONS else DeviceState.valueOf(word.uppercase())

    private fun connectionOf(serial: String, rest: String): Connection = when {
        serial.startsWith("emulator-") -> Connection.EMULATOR
        wirelessMdns.matches(serial) || hostPort.matches(serial) -> Connection.WIFI
        " usb:" in " $rest" -> Connection.USB
        else -> Connection.UNKNOWN
    }

    /** `Android Debug Bridge version 1.0.41` followed by `Version 35.0.0-12147458`; null when the text is not adb's version output. */
    fun parseVersion(output: String): AdbVersion? {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val first = lines.firstOrNull { it.startsWith("Android Debug Bridge version") } ?: return null
        val protocol = first.removePrefix("Android Debug Bridge version").trim()
        val build = lines.firstOrNull { it.startsWith("Version ") }?.removePrefix("Version ")?.trim()
        return if (protocol.isEmpty()) null else AdbVersion(protocol, build)
    }
}
