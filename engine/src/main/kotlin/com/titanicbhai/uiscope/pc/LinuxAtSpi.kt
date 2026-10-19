package com.titanicbhai.uiscope.pc

import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementNode
import java.awt.Rectangle
import java.awt.Robot
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class LinuxAtSpi : PcInspector {

    private var eventListener: (() -> Unit)? = null
    private val robot = runCatching { Robot() }.getOrNull()

    override fun isPermissionGranted(): Boolean {
        return isAtSpiAvailable()
    }

    override fun getPermissionInstructions(): PermissionInstructions {
        val distro = detectDistro()
        val installCmd = when {
            distro.contains("ubuntu", ignoreCase = true) ||
            distro.contains("debian", ignoreCase = true) ||
            distro.contains("mint", ignoreCase = true) ->
                "sudo apt install at-spi2-core"
            distro.contains("fedora", ignoreCase = true) ||
            distro.contains("rhel", ignoreCase = true) ->
                "sudo dnf install at-spi2-atk"
            distro.contains("arch", ignoreCase = true) ||
            distro.contains("manjaro", ignoreCase = true) ->
                "sudo pacman -S at-spi2-core"
            distro.contains("opensuse", ignoreCase = true) ->
                "sudo zypper install at-spi2-core"
            else -> "sudo apt install at-spi2-core  # or your distro's equivalent"
        }
        return PermissionInstructions(
            os = "Linux",
            title = "AT-SPI2 not found",
            steps = listOf(
                "AT-SPI2 (Assistive Technology Service Provider Interface) is required for PC inspection.",
                "Install it with:",
                "  $installCmd",
                "Then log out and log back in, or run:",
                "  /usr/lib/at-spi2-core/at-spi-bus-launcher --launch-immediately &",
                "Restart UIScope after installing."
            ),
            actionLabel = null,
            actionUrl = null
        )
    }

    override fun findElementAtPoint(screenX: Int, screenY: Int): ElementNode? {
        return try {
            if (!isAtSpiAvailable()) return null
            val result = runPython("""
import subprocess, sys, json

try:
    import pyatspi
    desktop = pyatspi.Registry.getDesktop(0)
    found = None
    best_depth = -1

    def search(obj, depth=0):
        global found, best_depth
        try:
            comp = obj.queryComponent()
            ext = comp.getExtents(pyatspi.XY_SCREEN)
            x, y, w, h = ext.x, ext.y, ext.width, ext.height
            if x <= $screenX <= x+w and y <= $screenY <= y+h:
                if depth > best_depth:
                    best_depth = depth
                    found = obj
            for i in range(obj.childCount):
                search(obj[i], depth+1)
        except Exception:
            pass

    for app in desktop:
        try:
            search(app)
        except Exception:
            pass

    if found:
        try:
            comp = found.queryComponent()
            ext = comp.getExtents(pyatspi.XY_SCREEN)
            name = found.name or ''
            role = found.getLocalizedRoleName() or ''
            state_set = found.getState()
            states = [str(s) for s in pyatspi.StateType._enum_lookup.values() if state_set.contains(s)]
            attrs = dict(found.getAttributes()) if found.getAttributes() else {}
            data = {
                'name': name,
                'role': role,
                'className': found.getRoleName(),
                'x': ext.x, 'y': ext.y, 'w': ext.width, 'h': ext.height,
                'enabled': state_set.contains(pyatspi.STATE_ENABLED),
                'focusable': state_set.contains(pyatspi.STATE_FOCUSABLE),
                'focused': state_set.contains(pyatspi.STATE_FOCUSED),
                'attrs': attrs,
                'depth': best_depth
            }
            print(json.dumps(data))
        except Exception as e:
            print(json.dumps({'error': str(e)}))
    else:
        print(json.dumps({'error': 'not_found'}))
except ImportError:
    print(json.dumps({'error': 'pyatspi_not_installed'}))
""")
            parseAtSpiJson(result)
        } catch (_: Exception) {
            findElementViaXdotool(screenX, screenY)
        }
    }

    override fun getRootTree(windowHandle: Long): List<ElementNode> {
        return try {
            if (!isAtSpiAvailable()) return getFallbackTree()
            val result = runPython("""
import json, sys
try:
    import pyatspi
    desktop = pyatspi.Registry.getDesktop(0)
    apps = []
    for app in desktop:
        try:
            children = []
            for j in range(min(app.childCount, 30)):
                try:
                    win = app[j]
                    try:
                        comp = win.queryComponent()
                        ext = comp.getExtents(pyatspi.XY_SCREEN)
                        bounds = {'x': ext.x, 'y': ext.y, 'w': ext.width, 'h': ext.height}
                    except Exception:
                        bounds = {'x': 0, 'y': 0, 'w': 0, 'h': 0}
                    children.append({
                        'name': win.name or '',
                        'role': win.getLocalizedRoleName() or '',
                        'className': win.getRoleName(),
                        'bounds': bounds,
                        'enabled': True,
                        'depth': 1
                    })
                except Exception:
                    pass
            apps.append({
                'name': app.name or '(unknown)',
                'role': 'application',
                'className': 'AtkObject',
                'bounds': {'x': 0, 'y': 0, 'w': 0, 'h': 0},
                'enabled': True,
                'depth': 0,
                'children': children
            })
        except Exception:
            pass
    print(json.dumps(apps))
except ImportError:
    print(json.dumps([]))
""")
            parseAtSpiTreeJson(result)
        } catch (_: Exception) {
            getFallbackTree()
        }
    }

    override fun captureWindowScreenshot(windowHandle: Long): ByteArray? {
        return try {
            if (windowHandle > 0L) {
                captureXWindow(windowHandle)
            } else {
                captureFullScreen()
            }
        } catch (_: Exception) {
            captureFullScreen()
        }
    }

    override fun getWindowHandleAt(screenX: Int, screenY: Int): Long {
        return try {
            val result = runCommand(listOf("xdotool", "getmouselocation", "--shell"))
            val windowLine = result.lines().firstOrNull { it.startsWith("WINDOW=") }
            windowLine?.removePrefix("WINDOW=")?.trim()?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    override fun getWindowInfo(handle: Long): PcWindowInfo? {
        return try {
            val output = runCommand(listOf("xdotool", "getwindowname", handle.toString()))
            val title = output.trim()
            val geomOut = runCommand(listOf("xdotool", "getwindowgeometry", handle.toString()))
            val posLine = geomOut.lines().firstOrNull { it.trim().startsWith("Position:") }
            val sizeLine = geomOut.lines().firstOrNull { it.trim().startsWith("Geometry:") }
            val pos = posLine?.substringAfter("Position:")?.trim()?.substringBefore(" ")?.split(",")
            val size = sizeLine?.substringAfter("Geometry:")?.trim()?.split("x")
            PcWindowInfo(
                handle = handle,
                title = title.ifBlank { "Window $handle" },
                bounds = Bounds(
                    x = pos?.getOrNull(0)?.toIntOrNull() ?: 0,
                    y = pos?.getOrNull(1)?.toIntOrNull() ?: 0,
                    width = size?.getOrNull(0)?.toIntOrNull() ?: 800,
                    height = size?.getOrNull(1)?.toIntOrNull() ?: 600
                )
            )
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

    private fun isAtSpiAvailable(): Boolean {
        return try {
            val result = runCommand(listOf("python3", "-c", "import pyatspi; print('ok')"))
            result.trim() == "ok"
        } catch (_: Exception) {
            false
        }
    }

    private fun detectDistro(): String {
        return try {
            runCommand(listOf("sh", "-c", "cat /etc/os-release 2>/dev/null | head -5"))
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseAtSpiJson(json: String): ElementNode? {
        return try {
            val map = parseSimpleJsonObject(json) ?: return null
            if (map["error"] != null) return null
            val attrs = parseSimpleJsonObject(map["attrs"]?.toString() ?: "{}") ?: emptyMap()
            val props = mutableMapOf<String, String?>()
            props["Role"] = map["role"]?.toString()
            props["IsKeyboardFocusable"] = map["focusable"]?.toString()
            props["IsFocused"] = map["focused"]?.toString()
            attrs.forEach { (k, v) -> props[k] = v?.toString() }
            ElementNode(
                id = "atspi_${System.currentTimeMillis()}",
                name = map["name"]?.toString() ?: "",
                className = map["className"]?.toString() ?: "",
                bounds = Bounds(
                    x = map["x"]?.toString()?.toIntOrNull() ?: 0,
                    y = map["y"]?.toString()?.toIntOrNull() ?: 0,
                    width = map["w"]?.toString()?.toIntOrNull() ?: 0,
                    height = map["h"]?.toString()?.toIntOrNull() ?: 0
                ),
                isEnabled = map["enabled"]?.toString() == "true",
                isFocused = map["focused"]?.toString() == "true",
                depth = map["depth"]?.toString()?.toIntOrNull() ?: 0,
                properties = props
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAtSpiTreeJson(json: String): List<ElementNode> {
        return try {
            val trimmed = json.trim()
            if (!trimmed.startsWith("[")) return emptyList()
            val items = splitJsonArray(trimmed)
            items.mapIndexedNotNull { idx, item ->
                val map = parseSimpleJsonObject(item) ?: return@mapIndexedNotNull null
                val childrenRaw = map["children"]?.toString() ?: "[]"
                val children = parseAtSpiTreeJson(childrenRaw)
                val boundsMap = parseSimpleJsonObject(map["bounds"]?.toString() ?: "{}") ?: emptyMap()
                val props = mutableMapOf<String, String?>()
                props["Role"] = map["role"]?.toString()
                ElementNode(
                    id = "atspi_${idx}_${System.currentTimeMillis()}",
                    name = map["name"]?.toString() ?: "(unknown)",
                    className = map["className"]?.toString() ?: "",
                    bounds = Bounds(
                        x = boundsMap["x"]?.toString()?.toIntOrNull() ?: 0,
                        y = boundsMap["y"]?.toString()?.toIntOrNull() ?: 0,
                        width = boundsMap["w"]?.toString()?.toIntOrNull() ?: 0,
                        height = boundsMap["h"]?.toString()?.toIntOrNull() ?: 0
                    ),
                    isEnabled = map["enabled"]?.toString() == "true",
                    depth = map["depth"]?.toString()?.toIntOrNull() ?: 0,
                    siblingIndex = idx,
                    properties = props,
                    children = children
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findElementViaXdotool(screenX: Int, screenY: Int): ElementNode? {
        return try {
            val windowId = runCommand(listOf("xdotool", "getmouselocation", "--shell"))
                .lines().firstOrNull { it.startsWith("WINDOW=") }
                ?.removePrefix("WINDOW=")?.trim() ?: return null
            val title = runCommand(listOf("xdotool", "getwindowname", windowId)).trim()
            val geom = runCommand(listOf("xdotool", "getwindowgeometry", windowId))
            val posLine = geom.lines().firstOrNull { it.trim().startsWith("Position:") }
            val sizeLine = geom.lines().firstOrNull { it.trim().startsWith("Geometry:") }
            val pos = posLine?.substringAfter("Position:")?.trim()?.substringBefore(" ")?.split(",")
            val size = sizeLine?.substringAfter("Geometry:")?.trim()?.split("x")
            val props = mutableMapOf<String, String?>()
            props["WindowId"] = windowId
            props["Role"] = "window"
            ElementNode(
                id = "xdo_$windowId",
                name = title,
                className = "XWindow",
                bounds = Bounds(
                    x = pos?.getOrNull(0)?.toIntOrNull() ?: screenX,
                    y = pos?.getOrNull(1)?.toIntOrNull() ?: screenY,
                    width = size?.getOrNull(0)?.toIntOrNull() ?: 400,
                    height = size?.getOrNull(1)?.toIntOrNull() ?: 300
                ),
                isEnabled = true,
                properties = props
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getFallbackTree(): List<ElementNode> {
        return try {
            val output = runCommand(listOf("xdotool", "search", "--onlyvisible", "--name", ""))
            val ids = output.lines().filter { it.isNotBlank() }.take(20)
            ids.mapIndexedNotNull { idx, id ->
                try {
                    val title = runCommand(listOf("xdotool", "getwindowname", id)).trim()
                    if (title.isBlank()) return@mapIndexedNotNull null
                    ElementNode(
                        id = "xdo_$id",
                        name = title,
                        className = "XWindow",
                        isEnabled = true,
                        depth = 0,
                        siblingIndex = idx,
                        properties = mapOf("WindowId" to id, "Role" to "window")
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun captureXWindow(windowId: Long): ByteArray? {
        return try {
            val tmpFile = java.io.File.createTempFile("uiscope_cap_", ".png")
            tmpFile.deleteOnExit()
            val result = ProcessBuilder("import", "-window", windowId.toString(), tmpFile.absolutePath)
                .redirectErrorStream(true).start()
            result.waitFor()
            if (tmpFile.exists() && tmpFile.length() > 0) {
                val bytes = tmpFile.readBytes()
                tmpFile.delete()
                bytes
            } else {
                tmpFile.delete()
                captureFullScreen()
            }
        } catch (_: Exception) {
            captureFullScreen()
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

    private fun runPython(script: String): String {
        return runCommand(listOf("python3", "-c", script.trimIndent()))
    }

    private fun runCommand(cmd: List<String>): String {
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseSimpleJsonObject(json: String): Map<String, Any?>? {
        return try {
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) return null
            val result = mutableMapOf<String, Any?>()
            val inner = trimmed.removePrefix("{").removeSuffix("}")
            val regex = Regex(""""(\w+)"\s*:\s*("(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?|true|false|null|\{[^}]*\}|\[[^\]]*\])""")
            regex.findAll(inner).forEach { match ->
                val key = match.groupValues[1]
                val raw = match.groupValues[2]
                result[key] = when {
                    raw.startsWith('"') -> raw.removeSurrounding("\"")
                    raw == "true" -> true
                    raw == "false" -> false
                    raw == "null" -> null
                    raw.toIntOrNull() != null -> raw.toInt()
                    else -> raw
                }
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    private fun splitJsonArray(json: String): List<String> {
        val items = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in json.indices) {
            when (json[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) { items.add(json.substring(start, i + 1)); start = -1 } }
            }
        }
        return items
    }
}
