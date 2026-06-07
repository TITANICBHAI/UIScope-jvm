package com.titanicbhai.uiscope.pc

import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementNode
import java.awt.Rectangle
import java.awt.Robot
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class MacOsAccessibility : PcInspector {

    private var eventListener: (() -> Unit)? = null
    private val robot = runCatching { Robot() }.getOrNull()

    override fun isPermissionGranted(): Boolean = try {
        val result = runCmd("osascript", "-e",
            "tell application \"System Events\" to get name of first process")
        result.trim().isNotEmpty()
    } catch (_: Exception) { false }

    override fun getPermissionInstructions() = PermissionInstructions(
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

    override fun findElementAtPoint(screenX: Int, screenY: Int): ElementNode? = try {
        val tree = buildDeepMacOsTree()
        val flat = mutableListOf<ElementNode>()
        fun flatten(n: ElementNode) { flat.add(n); n.children.forEach { flatten(it) } }
        tree.forEach { flatten(it) }
        flat.filter { n ->
            n.bounds?.let { b ->
                screenX >= b.x && screenX <= b.x + b.width &&
                screenY >= b.y && screenY <= b.y + b.height
            } ?: false
        }.maxByOrNull { it.depth } ?: tree.firstOrNull()
    } catch (_: Exception) { null }

    override fun getRootTree(windowHandle: Long): List<ElementNode> = try {
        val deep = buildDeepMacOsTree()
        if (deep.isNotEmpty()) deep else buildShallowMacOsTree()
    } catch (_: Exception) { emptyList() }

    override fun captureWindowScreenshot(windowHandle: Long): ByteArray? = try {
        val parts = runCmd("osascript", "-e", """
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
        """.trimIndent()).trim().split("|").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size >= 4) captureRegion(parts[0], parts[1], parts[2], parts[3])
        else captureFullScreen()
    } catch (_: Exception) { captureFullScreen() }

    override fun getWindowHandleAt(screenX: Int, screenY: Int): Long = 1L

    override fun getWindowInfo(handle: Long): PcWindowInfo? = try {
        val parts = runCmd("osascript", "-e", """
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
        """.trimIndent()).split("|")
        if (parts.size >= 6) PcWindowInfo(
            handle = handle,
            title  = parts.getOrElse(1) { parts[0] }.trim(),
            bounds = Bounds(
                parts[2].trim().toIntOrNull() ?: 0,
                parts[3].trim().toIntOrNull() ?: 0,
                parts[4].trim().toIntOrNull() ?: 800,
                parts[5].trim().toIntOrNull() ?: 600
            )
        ) else null
    } catch (_: Exception) { null }

    override fun startEventSubscription(onEvent: () -> Unit) { eventListener = onEvent }
    override fun stopEventSubscription() { eventListener = null }
    override fun dispose() { eventListener = null }

    // ── Deep AX tree walk — recurses into UI elements of each window ──────────

    private fun buildDeepMacOsTree(): List<ElementNode> {
        val script = """
tell application "System Events"
    set targetApp to (first process whose frontmost is true)
    set appName to name of targetApp
    set bundleId to bundle identifier of targetApp
    set result to {}
    set allWins to windows of targetApp
    set winIdx to 0
    repeat with w in allWins
        set winIdx to winIdx + 1
        set wTitle to title of w
        set wPos to position of w
        set wSize to size of w
        set end of result to ("WIN|" & winIdx & "|-1|NSWindow|" & wTitle & "|" & appName & "|" & (item 1 of wPos) & "|" & (item 2 of wPos) & "|" & (item 1 of wSize) & "|" & (item 2 of wSize) & "|" & bundleId)
        set lvl1 to UI elements of w
        set i1 to 0
        repeat with el1 in lvl1
            set i1 to i1 + 1
            try
                set r1 to role of el1
                set nm1 to ""
                try
                    set nm1 to name of el1
                end try
                set pos1 to position of el1
                set sz1 to size of el1
                set myId to winIdx * 10000 + i1
                set end of result to ("EL|" & myId & "|" & winIdx & "|" & r1 & "|" & nm1 & "||" & (item 1 of pos1) & "|" & (item 2 of pos1) & "|" & (item 1 of sz1) & "|" & (item 2 of sz1) & "|")
                set lvl2 to UI elements of el1
                set i2 to 0
                repeat with el2 in lvl2
                    set i2 to i2 + 1
                    try
                        set r2 to role of el2
                        set nm2 to ""
                        try
                            set nm2 to name of el2
                        end try
                        set pos2 to position of el2
                        set sz2 to size of el2
                        set myId2 to winIdx * 10000 + i1 * 100 + i2
                        set end of result to ("EL|" & myId2 & "|" & myId & "|" & r2 & "|" & nm2 & "||" & (item 1 of pos2) & "|" & (item 2 of pos2) & "|" & (item 1 of sz2) & "|" & (item 2 of sz2) & "|")
                        set lvl3 to UI elements of el2
                        set i3 to 0
                        repeat with el3 in lvl3
                            set i3 to i3 + 1
                            try
                                set r3 to role of el3
                                set nm3 to ""
                                try
                                    set nm3 to name of el3
                                end try
                                set pos3 to position of el3
                                set sz3 to size of el3
                                set myId3 to winIdx * 10000 + i1 * 100 + i2 * 10 + i3
                                set end of result to ("EL|" & myId3 & "|" & myId2 & "|" & r3 & "|" & nm3 & "||" & (item 1 of pos3) & "|" & (item 2 of pos3) & "|" & (item 1 of sz3) & "|" & (item 2 of sz3) & "|")
                            end try
                        end repeat
                    end try
                end repeat
            end try
        end repeat
    end repeat
    return result
end tell
        """.trimIndent()

        val output = runCmd("osascript", "-e", script)
        if (output.isBlank()) return emptyList()

        data class RawNode(val id: Int, val parentId: Int, val depth: Int, val node: ElementNode)

        val rawNodes = mutableListOf<RawNode>()
        val winIdMap = mutableMapOf<Int, Int>()

        output.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { entry ->
            val parts = entry.split("|")
            val type = parts.getOrNull(0) ?: return@forEach
            when (type) {
                "WIN" -> {
                    val id    = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return@forEach
                    val role  = parts.getOrNull(3)?.trim() ?: "NSWindow"
                    val title = parts.getOrNull(4)?.trim() ?: ""
                    val app   = parts.getOrNull(5)?.trim() ?: ""
                    val x     = parts.getOrNull(6)?.trim()?.toIntOrNull() ?: 0
                    val y     = parts.getOrNull(7)?.trim()?.toIntOrNull() ?: 0
                    val w     = parts.getOrNull(8)?.trim()?.toIntOrNull() ?: 800
                    val h     = parts.getOrNull(9)?.trim()?.toIntOrNull() ?: 600
                    val bundle = parts.getOrNull(10)?.trim()
                    val props = mutableMapOf<String, String?>(
                        "Role" to "AXWindow", "Subrole" to "AXStandardWindow",
                        "Application" to app, "IsKeyboardFocusable" to "true"
                    )
                    bundle?.let { props["BundleIdentifier"] = it }
                    rawNodes.add(RawNode(id, -1, 0, ElementNode(
                        id = "mac_$id", name = title.ifBlank { app },
                        className = role, bounds = Bounds(x, y, w, h),
                        isEnabled = true, depth = 0, properties = props
                    )))
                    winIdMap[id] = id
                }
                "EL" -> {
                    val id      = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return@forEach
                    val pid     = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: return@forEach
                    val role    = parts.getOrNull(3)?.trim() ?: "AXElement"
                    val name    = parts.getOrNull(4)?.trim() ?: ""
                    val x       = parts.getOrNull(6)?.trim()?.toIntOrNull() ?: 0
                    val y       = parts.getOrNull(7)?.trim()?.toIntOrNull() ?: 0
                    val w       = parts.getOrNull(8)?.trim()?.toIntOrNull() ?: 0
                    val h       = parts.getOrNull(9)?.trim()?.toIntOrNull() ?: 0
                    val parentDepth = rawNodes.find { it.id == pid }?.depth ?: 0
                    val props = mutableMapOf<String, String?>(
                        "Role" to role,
                        "IsKeyboardFocusable" to (role in setOf(
                            "AXButton", "AXTextField", "AXTextArea",
                            "AXCheckBox", "AXRadioButton", "AXComboBox",
                            "AXPopUpButton", "AXMenuItem", "AXSlider"
                        )).toString()
                    )
                    rawNodes.add(RawNode(id, pid, parentDepth + 1, ElementNode(
                        id = "mac_$id", name = name,
                        className = role,
                        bounds = if (w > 0 || h > 0) Bounds(x, y, w, h) else null,
                        isEnabled = true,
                        isClickable = role in setOf("AXButton", "AXMenuItem", "AXCheckBox",
                            "AXRadioButton", "AXPopUpButton", "AXComboBox"),
                        depth = parentDepth + 1,
                        properties = props
                    )))
                }
            }
        }

        if (rawNodes.isEmpty()) return emptyList()

        val nodeById = rawNodes.associateBy { it.id }
        val childrenByParent = mutableMapOf<Int, MutableList<RawNode>>()
        rawNodes.forEach { rn -> childrenByParent.getOrPut(rn.parentId) { mutableListOf() }.add(rn) }

        fun buildNode(rn: RawNode): ElementNode {
            val kids = childrenByParent[rn.id]?.mapIndexed { i, child ->
                buildNode(child).copy(siblingIndex = i, depth = rn.depth + 1)
            } ?: emptyList()
            return rn.node.copy(children = kids)
        }

        return childrenByParent[-1]?.mapIndexed { i, rn ->
            buildNode(rn).copy(siblingIndex = i)
        } ?: emptyList()
    }

    private fun buildShallowMacOsTree(): List<ElementNode> {
        val output = runCmd("osascript", "-e", """
tell application "System Events"
    set targetApp to (first process whose frontmost is true)
    set appName to name of targetApp
    set bundleId to bundle identifier of targetApp
    set result to {}
    set allWins to windows of targetApp
    set winCount to count of allWins
    repeat with i from 1 to winCount
        set w to item i of allWins
        set wTitle to title of w
        set wPos to position of w
        set wSize to size of w
        set end of result to (appName & "|" & wTitle & "|" & (item 1 of wPos) & "|" & (item 2 of wPos) & "|" & (item 1 of wSize) & "|" & (item 2 of wSize) & "|" & bundleId)
    end repeat
    return result
end tell
        """.trimIndent())
        return output.split(",").map { it.trim() }.filter { it.isNotBlank() }
            .mapIndexedNotNull { idx, line ->
                val parts = line.split("|")
                if (parts.size < 6) return@mapIndexedNotNull null
                val props = mutableMapOf<String, String?>(
                    "Role" to "AXWindow", "Subrole" to "AXStandardWindow",
                    "Application" to parts[0].trim(), "IsKeyboardFocusable" to "true"
                )
                parts.getOrNull(6)?.trim()?.let { props["BundleIdentifier"] = it }
                ElementNode(
                    id = "mac_win_$idx",
                    name = parts.getOrElse(1) { parts[0] }.trim(),
                    className = "NSWindow",
                    bounds = Bounds(
                        parts[2].trim().toIntOrNull() ?: 0,
                        parts[3].trim().toIntOrNull() ?: 0,
                        parts[4].trim().toIntOrNull() ?: 800,
                        parts[5].trim().toIntOrNull() ?: 600
                    ),
                    isEnabled = true, depth = 0, siblingIndex = idx, properties = props
                )
            }
    }

    private fun captureRegion(x: Int, y: Int, w: Int, h: Int): ByteArray? = try {
        val r = robot ?: return captureFullScreen()
        val img = r.createScreenCapture(Rectangle(x, y, w.coerceAtLeast(1), h.coerceAtLeast(1)))
        val baos = ByteArrayOutputStream(); ImageIO.write(img, "PNG", baos); baos.toByteArray()
    } catch (_: Exception) { null }

    private fun captureFullScreen(): ByteArray? = try {
        val r = robot ?: return null
        val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
        val img = r.createScreenCapture(Rectangle(0, 0, screen.width, screen.height))
        val baos = ByteArrayOutputStream(); ImageIO.write(img, "PNG", baos); baos.toByteArray()
    } catch (_: Exception) { null }

    private fun runCmd(vararg cmd: String): String = try {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor(); out
    } catch (_: Exception) { "" }
}
