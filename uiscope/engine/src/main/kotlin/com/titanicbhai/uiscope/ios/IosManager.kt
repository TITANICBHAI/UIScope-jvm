package com.titanicbhai.uiscope.ios

import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class IosManager {

    fun isIdbAvailable(): Boolean = runCatching {
        ProcessBuilder("idb", "--version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    fun isLibimobiledeviceAvailable(): Boolean = runCatching {
        ProcessBuilder("idevice_id", "--version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    fun isXcrunAvailable(): Boolean = runCatching {
        ProcessBuilder("xcrun", "simctl", "list", "--json").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    fun isAnyToolAvailable(): Boolean = isIdbAvailable() || isLibimobiledeviceAvailable() || isXcrunAvailable()

    suspend fun listDevices(): List<IosDevice> = withContext(Dispatchers.IO) {
        val devices = mutableListOf<IosDevice>()
        try { devices.addAll(listPhysicalDevices()) } catch (_: Exception) {}
        try { devices.addAll(listSimulators()) } catch (_: Exception) {}
        devices
    }

    private fun listPhysicalDevices(): List<IosDevice> {
        if (!isLibimobiledeviceAvailable()) {
            if (!isIdbAvailable()) return emptyList()
            return listPhysicalViaIdb()
        }
        return try {
            val output = runCmd("idevice_id", "-l")
            output.lines().filter { it.isNotBlank() }.mapNotNull { udid ->
                try {
                    val name = runCmd("ideviceinfo", "-u", udid.trim(), "-k", "DeviceName").trim()
                    val model = runCmd("ideviceinfo", "-u", udid.trim(), "-k", "ProductType").trim()
                    val version = runCmd("ideviceinfo", "-u", udid.trim(), "-k", "ProductVersion").trim()
                    IosDevice(
                        udid = udid.trim(),
                        name = name.ifBlank { "iPhone" },
                        model = model.ifBlank { null },
                        osVersion = version.ifBlank { null },
                        type = IosDeviceType.PHYSICAL
                    )
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun listPhysicalViaIdb(): List<IosDevice> = try {
        val output = runCmd("idb", "list-targets", "--json")
        output.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            try {
                val udid = Regex(""""udid"\s*:\s*"([^"]+)"""").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
                val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(line)?.groupValues?.get(1) ?: "iPhone"
                val targetType = Regex(""""target_type"\s*:\s*"([^"]+)"""").find(line)?.groupValues?.get(1) ?: ""
                val osVersion = Regex(""""os_version"\s*:\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
                if (targetType.contains("simulator", ignoreCase = true)) return@mapNotNull null
                IosDevice(udid = udid, name = name, osVersion = osVersion, type = IosDeviceType.PHYSICAL)
            } catch (_: Exception) { null }
        }
    } catch (_: Exception) { emptyList() }

    private fun listSimulators(): List<IosDevice> {
        if (!isXcrunAvailable()) return emptyList()
        return try {
            val json = runCmd("xcrun", "simctl", "list", "devices", "--json")
            val devicesSection = Regex(""""devices"\s*:\s*\{([\s\S]*)\}""").find(json)?.groupValues?.get(1) ?: return emptyList()
            val runtimePattern = Regex(""""(com\.apple\.CoreSimulator\.SimRuntime\.[^"]+)"\s*:\s*\[([\s\S]*?)\](?=\s*,\s*"|$|\s*\})")
            val devicePattern = Regex("""\{([^}]+)\}""")
            val udidRx = Regex(""""udid"\s*:\s*"([^"]+)"""")
            val nameRx = Regex(""""name"\s*:\s*"([^"]+)"""")
            val stateRx = Regex(""""state"\s*:\s*"([^"]+)"""")
            val result = mutableListOf<IosDevice>()
            runtimePattern.findAll(devicesSection).forEach { rtMatch ->
                val rtName = rtMatch.groupValues[1]
                    .substringAfterLast(".").replace("-", " ")
                val devicesJson = rtMatch.groupValues[2]
                devicePattern.findAll(devicesJson).forEach { devMatch ->
                    val devBlock = devMatch.groupValues[1]
                    val udid = udidRx.find(devBlock)?.groupValues?.get(1) ?: return@forEach
                    val name = nameRx.find(devBlock)?.groupValues?.get(1) ?: return@forEach
                    val state = stateRx.find(devBlock)?.groupValues?.get(1) ?: ""
                    if (state.equals("Booted", ignoreCase = true) || state.equals("Shutdown", ignoreCase = true)) {
                        result.add(IosDevice(
                            udid = udid,
                            name = name,
                            model = name,
                            osVersion = rtName,
                            type = IosDeviceType.SIMULATOR
                        ))
                    }
                }
            }
            result.take(20)
        } catch (_: Exception) { emptyList() }
    }

    suspend fun dumpAccessibilityTree(device: IosDevice): List<ElementNode> = withContext(Dispatchers.IO) {
        try {
            when {
                isIdbAvailable() -> dumpViaIdb(device)
                device.isSimulator && isXcrunAvailable() -> dumpSimulatorViaXcrun(device)
                else -> emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun dumpViaIdb(device: IosDevice): List<ElementNode> = try {
        val output = runCmd("idb", "ui", "describe-all", "--udid", device.udid)
        parseIdbOutput(output)
    } catch (_: Exception) { emptyList() }

    private fun dumpSimulatorViaXcrun(device: IosDevice): List<ElementNode> = try {
        val script = """
            tell application "Simulator" to activate
            delay 0.5
            tell application "System Events"
                set targetApp to first process whose frontmost is true
                set result to {}
                repeat with w in windows of targetApp
                    set wName to title of w
                    set wPos to position of w
                    set wSize to size of w
                    set end of result to ("WIN|" & wName & "|" & (item 1 of wPos) & "|" & (item 2 of wPos) & "|" & (item 1 of wSize) & "|" & (item 2 of wSize))
                    set kids to UI elements of w
                    repeat with el in kids
                        try
                            set elRole to role of el
                            set elName to name of el
                            set elPos to position of el
                            set elSz to size of el
                            set end of result to ("EL|" & elName & "|" & elRole & "|" & (item 1 of elPos) & "|" & (item 2 of elPos) & "|" & (item 1 of elSz) & "|" & (item 2 of elSz))
                        end try
                    end repeat
                end repeat
                return result
            end tell
        """.trimIndent()
        val output = runCmd("osascript", "-e", script)
        parseAppleScriptIosOutput(output)
    } catch (_: Exception) { emptyList() }

    private fun parseIdbOutput(raw: String): List<ElementNode> {
        val nodes = mutableListOf<ElementNode>()
        var idx = 0
        raw.lines().filter { it.isNotBlank() }.forEach { line ->
            try {
                val typeRx   = Regex("""type:\s*(\S+)""")
                val textRx   = Regex("""label:\s*"([^"]*)" """)
                val frameRx  = Regex("""frame:\s*\(\s*([\d.]+),\s*([\d.]+)\s*\)\s+([\d.]+)x([\d.]+)""")
                val enabledRx = Regex("""enabled:\s*(true|false)""")
                val type = typeRx.find(line)?.groupValues?.get(1) ?: "Element"
                val label = textRx.find(line)?.groupValues?.get(1) ?: ""
                val fr = frameRx.find(line)?.groupValues
                val enabled = enabledRx.find(line)?.groupValues?.get(1) != "false"
                val bounds = if (fr != null && fr.size >= 5)
                    Bounds(fr[1].toIntOrNull() ?: 0, fr[2].toIntOrNull() ?: 0,
                           fr[3].toIntOrNull() ?: 0, fr[4].toIntOrNull() ?: 0)
                else null
                nodes.add(ElementNode(
                    id = "ios_${idx++}",
                    name = label,
                    className = type,
                    bounds = bounds,
                    isEnabled = enabled,
                    depth = 0
                ))
            } catch (_: Exception) {}
        }
        return nodes
    }

    private fun parseAppleScriptIosOutput(raw: String): List<ElementNode> {
        val nodes = mutableListOf<ElementNode>()
        var depth = 0
        raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEachIndexed { idx, entry ->
            val parts = entry.split("|")
            when (parts.getOrNull(0)) {
                "WIN" -> {
                    depth = 0
                    if (parts.size >= 6) {
                        nodes.add(ElementNode(
                            id = "ios_win_$idx",
                            name = parts.getOrElse(1) { "Window" },
                            className = "UIWindow",
                            bounds = Bounds(parts[2].trim().toIntOrNull() ?: 0,
                                           parts[3].trim().toIntOrNull() ?: 0,
                                           parts[4].trim().toIntOrNull() ?: 0,
                                           parts[5].trim().toIntOrNull() ?: 0),
                            isEnabled = true, depth = 0
                        ))
                        depth = 1
                    }
                }
                "EL" -> {
                    if (parts.size >= 7) {
                        nodes.add(ElementNode(
                            id = "ios_el_$idx",
                            name = parts.getOrElse(1) { "" },
                            className = parts.getOrElse(2) { "UIElement" },
                            bounds = Bounds(parts[3].trim().toIntOrNull() ?: 0,
                                           parts[4].trim().toIntOrNull() ?: 0,
                                           parts[5].trim().toIntOrNull() ?: 0,
                                           parts[6].trim().toIntOrNull() ?: 0),
                            isEnabled = true, depth = depth
                        ))
                    }
                }
            }
        }
        return nodes
    }

    suspend fun captureScreenshot(device: IosDevice): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (device.isSimulator) captureSimulatorScreenshot(device.udid)
            else capturePhysicalScreenshot(device.udid)
        } catch (_: Exception) { null }
    }

    private fun captureSimulatorScreenshot(udid: String): ByteArray? = try {
        val tmp = File.createTempFile("uiscope_ios_", ".png").also { it.deleteOnExit() }
        val proc = ProcessBuilder("xcrun", "simctl", "io", udid, "screenshot", tmp.absolutePath)
            .redirectErrorStream(true).start()
        proc.waitFor()
        if (tmp.exists() && tmp.length() > 0) {
            val bytes = tmp.readBytes(); tmp.delete(); bytes
        } else { tmp.delete(); null }
    } catch (_: Exception) { null }

    private fun capturePhysicalScreenshot(udid: String): ByteArray? = try {
        if (isIdbAvailable()) {
            val tmp = File.createTempFile("uiscope_ios_", ".png").also { it.deleteOnExit() }
            val proc = ProcessBuilder("idb", "screenshot", "--udid", udid, tmp.absolutePath)
                .redirectErrorStream(true).start()
            proc.waitFor()
            if (tmp.exists() && tmp.length() > 0) {
                val bytes = tmp.readBytes(); tmp.delete(); bytes
            } else { tmp.delete(); null }
        } else if (isLibimobiledeviceAvailable()) {
            val tmp = File.createTempFile("uiscope_ios_", ".png").also { it.deleteOnExit() }
            val proc = ProcessBuilder("idevicescreenshot", "-u", udid, tmp.absolutePath)
                .redirectErrorStream(true).start()
            proc.waitFor()
            if (tmp.exists() && tmp.length() > 0) {
                val bytes = tmp.readBytes(); tmp.delete(); bytes
            } else { tmp.delete(); null }
        } else null
    } catch (_: Exception) { null }

    private fun runCmd(vararg cmd: String): String = try {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        out
    } catch (_: Exception) { "" }
}
