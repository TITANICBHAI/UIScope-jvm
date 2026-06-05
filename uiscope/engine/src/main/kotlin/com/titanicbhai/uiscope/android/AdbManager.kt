package com.titanicbhai.uiscope.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

class AdbManager(private var adbPath: String = "adb") {

    val currentAdbPath: String get() = adbPath

    fun setAdbPath(path: String) {
        adbPath = path.ifBlank { "adb" }
    }

    suspend fun dumpUiHierarchy(serial: String): String = withContext(Dispatchers.IO) {
        runAdbDevice(serial, "shell", "uiautomator", "dump", "/dev/stdout")
    }

    suspend fun captureScreenshotBytes(serial: String): ByteArray = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(adbPath, "-s", serial, "exec-out", "screencap", "-p"))
            .redirectErrorStream(false)
            .start()
        val bytes = process.inputStream.readBytes()
        process.waitFor()
        bytes
    }

    suspend fun pairDevice(address: String, code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val output = runAdb("pair", address.trim(), code.trim())
            output.contains("Successfully paired") || output.contains("success", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun runAdbDevice(serial: String, vararg args: String): String {
        val process = ProcessBuilder(listOf(adbPath, "-s", serial) + args.toList())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
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

    // ── ADB Auto-download (Plan §5 / §6) ─────────────────────────────────────

    /** Returns the OS-appropriate platform-tools download URL from Google. */
    fun getPlatformToolsDownloadUrl(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") ->
                "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
            os.contains("mac") ->
                "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip"
            else ->
                "https://dl.google.com/android/repository/platform-tools-latest-linux.zip"
        }
    }

    /**
     * Downloads the minimal ADB platform-tools from Google, extracts them to
     * `~/.uiscope/platform-tools/`, marks the binary executable, updates [adbPath],
     * and returns the absolute path to the adb binary on success, or null on failure.
     *
     * No admin/root required — everything goes into the user's home directory.
     */
    suspend fun downloadAndInstallAdb(onProgress: (String) -> Unit): String? =
        withContext(Dispatchers.IO) {
            try {
                val uiscopeDir = File(System.getProperty("user.home"), ".uiscope")
                uiscopeDir.mkdirs()
                val zipFile = File(uiscopeDir, "platform-tools.zip")

                onProgress("Downloading ADB platform-tools from Google…")
                val url = URL(getPlatformToolsDownloadUrl())
                val conn = url.openConnection()
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000
                conn.getInputStream().use { input ->
                    zipFile.outputStream().use { output -> input.copyTo(output) }
                }

                onProgress("Extracting platform-tools…")
                val extractDir = uiscopeDir
                ZipInputStream(zipFile.inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val target = File(extractDir, entry.name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }

                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                val adbBin = File(extractDir, "platform-tools/${if (isWindows) "adb.exe" else "adb"}")
                if (!adbBin.exists()) return@withContext null

                onProgress("Configuring ADB…")
                adbBin.setExecutable(true, false)
                zipFile.delete()
                setAdbPath(adbBin.absolutePath)

                adbBin.absolutePath
            } catch (e: Exception) {
                null
            }
        }

    // ── Internals ─────────────────────────────────────────────────────────────

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
