package com.titanicbhai.uiscope.pc

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementNode
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

private interface ExtUser32 : Library {
    fun WindowFromPoint(pt: WinDef.POINT): WinDef.HWND?
    companion object {
        val INSTANCE: ExtUser32? = runCatching { Native.load("user32", ExtUser32::class.java) }.getOrNull()
    }
}

class WindowsUiAutomation : PcInspector {

    private var eventListener: (() -> Unit)? = null
    private val robot = runCatching { Robot() }.getOrNull()

    override fun isPermissionGranted(): Boolean = try { User32.INSTANCE != null } catch (_: Exception) { true }

    override fun getPermissionInstructions() = PermissionInstructions(
        os = "Windows",
        title = "Limited account note",
        steps = listOf(
            "UIAutomation works for most elements without elevation.",
            "For elevated (administrator) windows, run UIScope as Administrator.",
            "Right-click UIScope → Run as administrator"
        ),
        actionLabel = null,
        actionUrl = null
    )

    override fun findElementAtPoint(screenX: Int, screenY: Int): ElementNode? = try {
        val tree = buildTreeViaUia("POINT", screenX.toString(), screenY.toString())
        if (tree.isNotEmpty()) {
            val flat = mutableListOf<ElementNode>()
            fun flatten(n: ElementNode) { flat.add(n); n.children.forEach { flatten(it) } }
            tree.forEach { flatten(it) }
            flat.filter { n ->
                n.bounds?.let { b ->
                    screenX >= b.x && screenX <= b.x + b.width &&
                    screenY >= b.y && screenY <= b.y + b.height
                } ?: false
            }.maxByOrNull { it.depth } ?: tree.firstOrNull()
        } else {
            val hwnd = findWindowAt(screenX, screenY) ?: return null
            val fallback = buildWindowTreeFallback(hwnd)
            val flat = mutableListOf<ElementNode>()
            fun flatten(n: ElementNode) { flat.add(n); n.children.forEach { flatten(it) } }
            fallback.forEach { flatten(it) }
            flat.filter { n ->
                n.bounds?.let { b ->
                    screenX >= b.x && screenX <= b.x + b.width &&
                    screenY >= b.y && screenY <= b.y + b.height
                } ?: false
            }.maxByOrNull { it.depth }
        }
    } catch (_: Exception) { null }

    override fun getRootTree(windowHandle: Long): List<ElementNode> = try {
        val uiaTree = buildTreeViaUia(windowHandle.toString())
        if (uiaTree.isNotEmpty()) uiaTree
        else buildWindowTreeFallback(WinDef.HWND(Pointer(windowHandle)))
    } catch (_: Exception) { emptyList() }

    override fun captureWindowScreenshot(windowHandle: Long): ByteArray? = try {
        val hwnd = WinDef.HWND(Pointer(windowHandle))
        val rect = WinDef.RECT()
        User32.INSTANCE.GetWindowRect(hwnd, rect)
        captureRegion(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
    } catch (_: Exception) { null }

    override fun getWindowHandleAt(screenX: Int, screenY: Int): Long = try {
        val point = WinDef.POINT().also { it.x = screenX; it.y = screenY }
        val hwnd = ExtUser32.INSTANCE?.WindowFromPoint(point)
        if (hwnd == null) 0L else Pointer.nativeValue(hwnd.pointer)
    } catch (_: Exception) { 0L }

    override fun getWindowInfo(handle: Long): PcWindowInfo? {
        if (handle == 0L) return null
        return try {
            val hwnd = WinDef.HWND(Pointer(handle))
            val titleBuf = CharArray(512)
            User32.INSTANCE.GetWindowText(hwnd, titleBuf, titleBuf.size)
            val title = String(titleBuf).trimEnd('\u0000')
            val rect = WinDef.RECT()
            User32.INSTANCE.GetWindowRect(hwnd, rect)
            PcWindowInfo(
                handle = handle,
                title = title.ifBlank { "Unknown Window" },
                bounds = Bounds(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
            )
        } catch (_: Exception) { null }
    }

    override fun startEventSubscription(onEvent: () -> Unit) { eventListener = onEvent }
    override fun stopEventSubscription() { eventListener = null }
    override fun dispose() { eventListener = null }

    // ── UIAutomation via PowerShell (real IUIAutomationElement) ──────────────

    private val psScriptContent = """
Add-Type -AssemblyName UIAutomationClient -ErrorAction SilentlyContinue
Add-Type -AssemblyName UIAutomationTypes -ErrorAction SilentlyContinue
${'$'}flat = [System.Collections.ArrayList]::new()
${'$'}script:idx = 0
function Walk(${'$'}el, ${'$'}pid, ${'$'}d) {
    if (${'$'}el -eq ${'$'}null -or ${'$'}d -gt 10) { return }
    try {
        ${'$'}cur = ${'$'}el.Current
        ${'$'}r = ${'$'}cur.BoundingRectangle
        ${'$'}myId = ${'$'}script:idx; ${'$'}script:idx++
        ${'$'}n = (${'$'}cur.Name -replace '["\r\n\\\\]',''); if (${'$'}n.Length -gt 120) { ${'$'}n = ${'$'}n.Substring(0,120) }
        ${'$'}c = (${'$'}cur.ClassName -replace '["\r\n\\\\]','')
        ${'$'}t = (${'$'}cur.ControlType.ProgrammaticName -replace 'ControlType\.','')
        ${'$'}a = (${'$'}cur.AutomationId -replace '["\r\n\\\\]','')
        ${'$'}xe = [int]${'$'}r.X; ${'$'}ye = [int]${'$'}r.Y; ${'$'}we = [int]${'$'}r.Width; ${'$'}he = [int]${'$'}r.Height
        ${'$'}en = [int][bool]${'$'}cur.IsEnabled; ${'$'}of = [int][bool]${'$'}cur.IsOffscreen; ${'$'}foc = [int][bool]${'$'}cur.IsKeyboardFocusable
        [void]${'$'}flat.Add("{`"i`":${'$'}myId,`"p`":${'$'}pid,`"d`":${'$'}d,`"n`":`"${'$'}n`",`"c`":`"${'$'}c`",`"t`":`"${'$'}t`",`"a`":`"${'$'}a`",`"x`":${'$'}xe,`"y`":${'$'}ye,`"w`":${'$'}we,`"h`":${'$'}he,`"e`":${'$'}en,`"o`":${'$'}of,`"f`":${'$'}foc}")
    } catch { return }
    ${'$'}walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
    ${'$'}child = ${'$'}walker.GetFirstChild(${'$'}el); ${'$'}cnt = 0
    while (${'$'}child -ne ${'$'}null -and ${'$'}cnt -lt 50) {
        Walk ${'$'}child ${'$'}myId (${'$'}d + 1)
        ${'$'}child = ${'$'}walker.GetNextSibling(${'$'}child); ${'$'}cnt++
    }
}
${'$'}arg0 = ${'$'}args[0]
try {
    if (${'$'}arg0 -eq 'POINT') {
        ${'$'}pt = New-Object System.Windows.Point([int]${'$'}args[1], [int]${'$'}args[2])
        ${'$'}root = [System.Windows.Automation.AutomationElement]::FromPoint(${'$'}pt)
    } else {
        ${'$'}root = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr][long]${'$'}arg0)
    }
    Walk ${'$'}root -1 0
    Write-Output ("[" + (${'$'}flat -join ",") + "]")
} catch {
    Write-Output "[]"
}
""".trimIndent()

    private fun buildTreeViaUia(vararg args: String): List<ElementNode> = try {
        val scriptFile = File.createTempFile("uiscope_uia_", ".ps1").also { it.deleteOnExit() }
        scriptFile.writeText(psScriptContent)
        val cmd = mutableListOf(
            "powershell.exe", "-ExecutionPolicy", "Bypass", "-NonInteractive",
            "-File", scriptFile.absolutePath
        )
        cmd.addAll(args)
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        scriptFile.delete()
        val trimmed = output.trim()
        if (trimmed.isBlank() || !trimmed.startsWith("[")) emptyList()
        else parseFlatUiaJson(trimmed)
    } catch (_: Exception) { emptyList() }

    private fun parseFlatUiaJson(json: String): List<ElementNode> {
        data class FlatEntry(
            val idx: Int, val parentIdx: Int, val depth: Int,
            val name: String, val className: String, val controlType: String,
            val automationId: String,
            val x: Int, val y: Int, val w: Int, val h: Int,
            val enabled: Boolean, val offscreen: Boolean, val focusable: Boolean
        )

        val entries = mutableListOf<FlatEntry>()
        val objRx   = Regex("""\{[^{}]+\}""")
        val iRx     = Regex(""""i"\s*:\s*(-?\d+)""")
        val pRx     = Regex(""""p"\s*:\s*(-?\d+)""")
        val dRx     = Regex(""""d"\s*:\s*(-?\d+)""")
        val nRx     = Regex(""""n"\s*:\s*"([^"]*)"""")
        val cRx     = Regex(""""c"\s*:\s*"([^"]*)"""")
        val tRx     = Regex(""""t"\s*:\s*"([^"]*)"""")
        val aRx     = Regex(""""a"\s*:\s*"([^"]*)"""")
        val xRx     = Regex(""""x"\s*:\s*(-?\d+)""")
        val yRx     = Regex(""""y"\s*:\s*(-?\d+)""")
        val wRx     = Regex(""""w"\s*:\s*(\d+)""")
        val hRx     = Regex(""""h"\s*:\s*(\d+)""")
        val eRx     = Regex(""""e"\s*:\s*(\d)""")
        val oRx     = Regex(""""o"\s*:\s*(\d)""")
        val fRx     = Regex(""""f"\s*:\s*(\d)""")

        objRx.findAll(json).forEach { m ->
            val obj = m.value
            val i   = iRx.find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            val p   = pRx.find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val d   = dRx.find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            entries.add(FlatEntry(
                idx         = i,
                parentIdx   = p,
                depth       = d,
                name        = nRx.find(obj)?.groupValues?.get(1) ?: "",
                className   = cRx.find(obj)?.groupValues?.get(1) ?: "",
                controlType = tRx.find(obj)?.groupValues?.get(1) ?: "",
                automationId= aRx.find(obj)?.groupValues?.get(1) ?: "",
                x           = xRx.find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                y           = yRx.find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                w           = wRx.find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                h           = hRx.find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                enabled     = eRx.find(obj)?.groupValues?.get(1) == "1",
                offscreen   = oRx.find(obj)?.groupValues?.get(1) == "1",
                focusable   = fRx.find(obj)?.groupValues?.get(1) == "1"
            ))
        }

        if (entries.isEmpty()) return emptyList()

        val nodeMap = mutableMapOf<Int, ElementNode>()
        val childrenMap = mutableMapOf<Int, MutableList<ElementNode>>()

        entries.sortedBy { it.idx }.forEach { entry ->
            val props = mutableMapOf<String, String?>()
            if (entry.automationId.isNotBlank()) props["AutomationId"] = entry.automationId
            props["ControlType"] = entry.controlType.ifBlank { "Pane" }
            props["IsKeyboardFocusable"] = entry.focusable.toString()
            props["IsOffscreen"] = entry.offscreen.toString()

            val node = ElementNode(
                id         = "uia_${entry.idx}",
                name       = entry.name,
                className  = entry.className.ifBlank { entry.controlType },
                bounds     = if (entry.w > 0 || entry.h > 0)
                                 Bounds(entry.x, entry.y, entry.w, entry.h)
                             else null,
                isEnabled  = entry.enabled,
                isClickable = entry.controlType in setOf("Button", "MenuItem", "Hyperlink", "CheckBox", "RadioButton", "ComboBox", "ListItem"),
                depth      = entry.depth,
                siblingIndex = 0,
                properties = props
            )
            nodeMap[entry.idx] = node
            childrenMap.getOrPut(entry.parentIdx) { mutableListOf() }.add(node)
        }

        fun buildNode(idx: Int): ElementNode {
            val node = nodeMap[idx] ?: return ElementNode(id = "uia_$idx", name = "", className = "", depth = 0)
            val kids = childrenMap[idx]?.mapIndexed { i, child ->
                val childIdx = child.id.removePrefix("uia_").toIntOrNull() ?: return@mapIndexed child
                buildNode(childIdx).copy(siblingIndex = i)
            } ?: emptyList()
            return node.copy(children = kids)
        }

        val roots = childrenMap[-1] ?: emptyList()
        return roots.mapIndexed { i, rootNode ->
            val rootIdx = rootNode.id.removePrefix("uia_").toIntOrNull() ?: return@mapIndexed rootNode
            buildNode(rootIdx).copy(siblingIndex = i)
        }
    }

    // ── Fallback: Win32 HWND enumeration (works for legacy apps) ─────────────

    private fun findWindowAt(screenX: Int, screenY: Int): WinDef.HWND? = try {
        val point = WinDef.POINT().also { it.x = screenX; it.y = screenY }
        ExtUser32.INSTANCE?.WindowFromPoint(point)
    } catch (_: Exception) { null }

    private fun buildWindowTreeFallback(hwnd: WinDef.HWND): List<ElementNode> = try {
        val rect = WinDef.RECT()
        User32.INSTANCE.GetWindowRect(hwnd, rect)
        val titleBuf = CharArray(512)
        User32.INSTANCE.GetWindowText(hwnd, titleBuf, titleBuf.size)
        val title = String(titleBuf).trimEnd('\u0000')
        val classBuf = CharArray(256)
        User32.INSTANCE.GetClassName(hwnd, classBuf, classBuf.size)
        val className = String(classBuf).trimEnd('\u0000')
        val handle = Pointer.nativeValue(hwnd.pointer)
        val isEnabled = User32.INSTANCE.IsWindowEnabled(hwnd)
        val isVisible = User32.INSTANCE.IsWindowVisible(hwnd)
        val props = mutableMapOf<String, String?>(
            "Handle" to "0x${handle.toString(16).uppercase()}",
            "IsVisible" to isVisible.toString(),
            "ControlType" to "Window",
            "IsKeyboardFocusable" to "true",
            "IsOffscreen" to (!isVisible).toString()
        )
        listOf(ElementNode(
            id       = "hwnd_$handle",
            name     = title.ifBlank { className },
            className = className,
            bounds   = Bounds(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top),
            isEnabled = isEnabled,
            depth    = 0,
            properties = props,
            children = collectChildWindows(hwnd, depth = 1)
        ))
    } catch (_: Exception) { emptyList() }

    private fun collectChildWindows(parent: WinDef.HWND, depth: Int): List<ElementNode> {
        if (depth > 6) return emptyList()
        val children = mutableListOf<ElementNode>()
        var siblingIdx = 0
        User32.INSTANCE.EnumChildWindows(parent, { hwnd, _ ->
            try {
                val parentHwnd = User32.INSTANCE.GetParent(hwnd)
                if (parentHwnd?.pointer == parent.pointer) {
                    val rect = WinDef.RECT()
                    User32.INSTANCE.GetWindowRect(hwnd, rect)
                    val classBuf = CharArray(256)
                    User32.INSTANCE.GetClassName(hwnd, classBuf, classBuf.size)
                    val className = String(classBuf).trimEnd('\u0000')
                    val titleBuf = CharArray(512)
                    User32.INSTANCE.GetWindowText(hwnd, titleBuf, titleBuf.size)
                    val title = String(titleBuf).trimEnd('\u0000')
                    val handle = Pointer.nativeValue(hwnd.pointer)
                    val isEnabled = User32.INSTANCE.IsWindowEnabled(hwnd)
                    val isVisible = User32.INSTANCE.IsWindowVisible(hwnd)
                    val props = mutableMapOf<String, String?>(
                        "Handle" to "0x${handle.toString(16).uppercase()}",
                        "IsVisible" to isVisible.toString(),
                        "ControlType" to guessControlType(className),
                        "IsKeyboardFocusable" to isKeyboardFocusable(className).toString(),
                        "IsOffscreen" to (!isVisible).toString()
                    )
                    children.add(ElementNode(
                        id          = "hwnd_${handle}_d$depth",
                        name        = title.ifBlank { className },
                        className   = className,
                        bounds      = Bounds(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top),
                        isEnabled   = isEnabled,
                        isClickable = true,
                        depth       = depth,
                        siblingIndex = siblingIdx++,
                        properties  = props,
                        children    = collectChildWindows(hwnd, depth + 1)
                    ))
                }
            } catch (_: Exception) {}
            true
        }, null)
        return children
    }

    private fun guessControlType(cls: String): String = when {
        cls.contains("Button", ignoreCase = true) -> "Button"
        cls.contains("Edit", ignoreCase = true) -> "Edit"
        cls.contains("Static", ignoreCase = true) -> "Text"
        cls.contains("ListBox", ignoreCase = true) -> "List"
        cls.contains("ComboBox", ignoreCase = true) -> "ComboBox"
        cls.contains("TreeView", ignoreCase = true) -> "Tree"
        cls.contains("ListView", ignoreCase = true) -> "DataGrid"
        cls.contains("ScrollBar", ignoreCase = true) -> "ScrollBar"
        cls.contains("ToolBar", ignoreCase = true) -> "ToolBar"
        cls.contains("Tab", ignoreCase = true) -> "Tab"
        else -> "Pane"
    }

    private fun isKeyboardFocusable(cls: String): Boolean =
        cls.contains("Edit", ignoreCase = true) ||
        cls.contains("Button", ignoreCase = true) ||
        cls.contains("ComboBox", ignoreCase = true) ||
        cls.contains("ListBox", ignoreCase = true)

    private fun captureRegion(x: Int, y: Int, w: Int, h: Int): ByteArray? = try {
        val r = robot ?: return null
        val img: BufferedImage = r.createScreenCapture(Rectangle(x, y, w.coerceAtLeast(1), h.coerceAtLeast(1)))
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", baos)
        baos.toByteArray()
    } catch (_: Exception) { null }
}
