package com.titanicbhai.uiscope.pc

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementNode
import java.awt.Desktop
import java.awt.Rectangle
import java.awt.Robot
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.imageio.ImageIO

class MacOsAccessibility : PcInspector {

    private var eventListener: (() -> Unit)? = null
    private val robot = runCatching { Robot() }.getOrNull()

    override fun isPermissionGranted(): Boolean {
        return try {
            val result = runCommand(
                listOf("python3", "-c",
                    "import subprocess; r=subprocess.run(['osascript','-e','tell application \"System Events\" to get name of first process'],capture_output=True,text=True); print('ok' if r.returncode==0 else 'no')")
            )
            result.trim() == "ok"
        } catch (_: Exception) {
            false
        }
    }

    override fun getPermissionInstructions(): PermissionInstructions {
        return PermissionInstructions(
            os = "macOS",
            title = "Accessibility permission required",
            steps = listOf(
                "Open System Settings",
                "Go to Privacy & Security → Accessibility",
                "Find UIScope in the list and toggle it ON",
                "If UIScope is not listed, click '+' and add it"
            ),
            actionLabel = "Open Privacy & Security",
            actionUrl = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"
        )
    }

    override fun findElementAtPoint(screenX: Int, screenY: Int): ElementNode? {
        return try {
            val script = """
                tell application "System Events"
                    set targetApp to (first process whose frontmost is true)
                    set appName to name of targetApp
                    set allWins to windows of targetApp
                    if (count of allWins) > 0 then
                        set w to item 1 of allWins
                        set wTitle to title of w
                        set wPos to position of w
                        set wSize to size of w
                        return appName & "|" & wTitle & "|" & (item 1 of wPos) & "|" & (item 2 of wPos) & "|" & (item 1 of wSize) & "|" & (item 2 of wSize)
                    else
                        return appName & "|" & appName & "|0|0|800|600"
                    end if
                end tell
            """.trimIndent()
            val result = runAppleScript(script)
            parseAppleScriptElement(result, screenX, screenY, depth = 0)
        } catch (_: Exception) {
            null
        }
    }

    override fun getRootTree(windowHandle: Long): List<ElementNode> {
        return try {
            buildMacOsTree()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun captureWindowScreenshot(windowHandle: Long): ByteArray? {
        return try {
            val script = """
                tell application "System Events"
                    set targetApp to (first process whose frontmost is true)
                    set allWins to windows of targetApp
                    if (count of allWins) > 0 then
                        set w to item 1 of allWins
                        set wPos to position of w
                        set wSize to size of w
                        return (item 1 of wPos) & "|" & (item 2 of wPos) & "|" & (item 1 of wSize) & "|" & (item 2 of wSize)
                    end if
                end tell
            """.trimIndent()
            val result = runAppleScript(script).trim()
            val parts = result.split("|").mapNotNull { it.trim().toIntOrNull() }
            if (parts.size >= 4) {
                captureRegion(parts[0], parts[1], parts[2], parts[3])
            } else {
                captureFullScreen()
            }
        } catch (_: Exception) {
            captureFullScreen()
        }
    }

    override fun getWindowHandleAt(screenX: Int, screenY: Int): Long {
        return 1L
    }

    override fun getWindowInfo(handle: Long): PcWindowInfo? {
        return try {
            val script = """
                tell application "System Events"
                    set targetApp to (first process whose frontmost is true)
                    set appName to name of targetApp
                    set allWins to windows of targetApp
                    if (count of allWins) > 0 then
                        set w to item 1 of allWins
                        set wTitle to title of w
                        set wPos to position of w
                        set wSize to size of w
                        return appName & "|" & wTitle & "|" & (item 1 of wPos) & "|" & (item 2 of wPos) & "|" & (item 1 of wSize) & "|" & (item 2 of wSize)
                    end if
                end tell
            """.trimIndent()
            val parts = runAppleScript(script).split("|")
            if (parts.size >= 6) {
                PcWindowInfo(
                    handle = handle,
                    title = parts.getOrElse(1) { parts[0] },
                    bounds = Bounds(
                        x = parts[2].trim().toIntOrNull() ?: 0,
                        y = parts[3].trim().toIntOrNull() ?: 0,
                        width = parts[4].trim().toIntOrNull() ?: 800,
                        height = parts[5].trim().toIntOrNull() ?: 600
                    )
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    override fun startEventSubscription(onEvent: () -> Unit) {
        eventListener = onEvent
    }

    override fun stopEventSubscription() {
        eventListener = null
    }

    override fun dispose() {
        eventListener = null
    }

    private fun buildMacOsTree(): List<ElementNode> {
        val script = """
            tell application "System Events"
                set targetApp to (first process whose frontmost is true)
                set appName to name of targetApp
                set appBundleId to bundle identifier of targetApp
                set result to {}
                set allWins to windows of targetApp
                set winCount to count of allWins
                repeat with i from 1 to winCount
                    set w to item i of allWins
                    set wTitle to title of w
                    set wPos to position of w
                    set wSize to size of w
                    set end of result to (appName & "|" & wTitle & "|" & (item 1 of wPos) & "|" & (item 2 of wPos) & "|" & (item 1 of wSize) & "|" & (item 2 of wSize) & "|" & appBundleId)
                end repeat
                return result
            end tell
        """.trimIndent()
        val output = runAppleScript(script)
        val lines = output.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return lines.mapIndexedNotNull { idx, line ->
            val parts = line.split("|")
            if (parts.size >= 6) {
                val props = mutableMapOf<String, String?>()
                parts.getOrNull(6)?.trim()?.let { props["BundleIdentifier"] = it }
                props["Role"] = "AXWindow"
                props["Subrole"] = "AXStandardWindow"
                props["IsKeyboardFocusable"] = "true"
                ElementNode(
                    id = "mac_win_$idx",
                    name = parts.getOrElse(1) { parts[0] }.trim(),
                    className = "NSWindow",
                    bounds = Bounds(
                        x = parts[2].trim().toIntOrNull() ?: 0,
                        y = parts[3].trim().toIntOrNull() ?: 0,
                        width = parts[4].trim().toIntOrNull() ?: 800,
                        height = parts[5].trim().toIntOrNull() ?: 600
                    ),
                    isEnabled = true,
                    depth = 0,
                    siblingIndex = idx,
                    properties = props
                )
            } else null
        }
    }

    private fun parseAppleScriptElement(raw: String, screenX: Int, screenY: Int, depth: Int): ElementNode? {
        val parts = raw.trim().split("|")
        if (parts.size < 6) return null
        val appName = parts[0].trim()
        val title = parts[1].trim()
        val x = parts[2].trim().toIntOrNull() ?: 0
        val y = parts[3].trim().toIntOrNull() ?: 0
        val w = parts[4].trim().toIntOrNull() ?: 800
        val h = parts[5].trim().toIntOrNull() ?: 600
        val props = mutableMapOf<String, String?>()
        props["Role"] = "AXWindow"
        props["Application"] = appName
        props["IsKeyboardFocusable"] = "true"
        return ElementNode(
            id = "mac_${System.currentTimeMillis()}",
            name = title.ifBlank { appName },
            className = "NSWindow",
            bounds = Bounds(x, y, w, h),
            isEnabled = true,
            depth = depth,
            properties = props
        )
    }

    private fun captureRegion(x: Int, y: Int, w: Int, h: Int): ByteArray? {
        return try {
            val r = robot ?: return captureFullScreen()
            val img = r.createScreenCapture(Rectangle(x, y, w.coerceAtLeast(1), h.coerceAtLeast(1)))
            val baos = ByteArrayOutputStream()
            ImageIO.write(img, "PNG", baos)
            baos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun captureFullScreen(): ByteArray? {
        return try {
            val r = robot ?: return null
            val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
            val img = r.createScreenCapture(Rectangle(0, 0, screen.width, screen.height))
            val baos = ByteArrayOutputStream()
            ImageIO.write(img, "PNG", baos)
            baos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun runAppleScript(script: String): String {
        return runCommand(listOf("osascript", "-e", script))
    }

    private fun runCommand(cmd: List<String>): String {
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out
    }
}
