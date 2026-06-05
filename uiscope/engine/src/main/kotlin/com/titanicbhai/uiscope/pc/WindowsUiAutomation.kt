package com.titanicbhai.uiscope.pc

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementNode
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO

private interface ExtUser32 : Library {
    fun WindowFromPoint(pt: WinDef.POINT): WinDef.HWND?

    companion object {
        val INSTANCE: ExtUser32? = runCatching {
            Native.load("user32", ExtUser32::class.java)
        }.getOrNull()
    }
}

class WindowsUiAutomation : PcInspector {

    private var eventListener: (() -> Unit)? = null
    private val robot = runCatching { Robot() }.getOrNull()

    override fun isPermissionGranted(): Boolean {
        return try {
            val user32 = User32.INSTANCE
            user32 != null
        } catch (_: Exception) {
            true
        }
    }

    override fun getPermissionInstructions(): PermissionInstructions? {
        return PermissionInstructions(
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
    }

    override fun findElementAtPoint(screenX: Int, screenY: Int): ElementNode? {
        return try {
            val hwnd = findWindowAt(screenX, screenY) ?: return null
            val tree = buildElementTreeFromPoint(hwnd, screenX, screenY)
            tree.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    override fun getRootTree(windowHandle: Long): List<ElementNode> {
        return try {
            buildWindowTree(WinDef.HWND(Pointer(windowHandle)))
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun captureWindowScreenshot(windowHandle: Long): ByteArray? {
        return try {
            val hwnd = WinDef.HWND(Pointer(windowHandle))
            val rect = WinDef.RECT()
            User32.INSTANCE.GetWindowRect(hwnd, rect)
            val x = rect.left
            val y = rect.top
            val w = (rect.right - rect.left).coerceAtLeast(1)
            val h = (rect.bottom - rect.top).coerceAtLeast(1)
            captureRegion(x, y, w, h)
        } catch (_: Exception) {
            null
        }
    }

    override fun getWindowHandleAt(screenX: Int, screenY: Int): Long {
        return try {
            val point = WinDef.POINT()
            point.x = screenX
            point.y = screenY
            val hwnd = ExtUser32.INSTANCE?.WindowFromPoint(point) ?: return 0L
            Pointer.nativeValue(hwnd.pointer)
        } catch (_: Exception) {
            0L
        }
    }

    override fun getWindowInfo(handle: Long): PcWindowInfo? {
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

    private fun findWindowAt(screenX: Int, screenY: Int): WinDef.HWND? {
        return try {
            val point = WinDef.POINT()
            point.x = screenX
            point.y = screenY
            ExtUser32.INSTANCE?.WindowFromPoint(point)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildElementTreeFromPoint(hwnd: WinDef.HWND, screenX: Int, screenY: Int): List<ElementNode> {
        val tree = buildWindowTree(hwnd)
        val flatList = mutableListOf<ElementNode>()
        fun flatten(node: ElementNode) { flatList.add(node); node.children.forEach { flatten(it) } }
        tree.forEach { flatten(it) }
        val hit = flatList.filter { n ->
            n.bounds?.let { b ->
                screenX >= b.x && screenX <= b.x + b.width && screenY >= b.y && screenY <= b.y + b.height
            } ?: false
        }.maxByOrNull { it.depth }
        return listOfNotNull(hit)
    }

    private fun buildWindowTree(hwnd: WinDef.HWND): List<ElementNode> {
        return try {
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

            val rootProps = mutableMapOf<String, String?>()
            rootProps["Handle"] = "0x${handle.toString(16).uppercase()}"
            rootProps["IsVisible"] = isVisible.toString()
            rootProps["ControlType"] = "Window"
            rootProps["IsKeyboardFocusable"] = "true"
            rootProps["IsOffscreen"] = (!isVisible).toString()

            val root = ElementNode(
                id = "hwnd_$handle",
                name = title.ifBlank { className },
                className = className,
                bounds = Bounds(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top),
                isEnabled = isEnabled,
                depth = 0,
                properties = rootProps,
                children = collectChildWindows(hwnd, depth = 1)
            )
            listOf(root)
        } catch (_: Exception) {
            emptyList()
        }
    }

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

                    val props = mutableMapOf<String, String?>()
                    props["Handle"] = "0x${handle.toString(16).uppercase()}"
                    props["IsVisible"] = isVisible.toString()
                    props["ControlType"] = guessControlType(className)
                    props["IsKeyboardFocusable"] = isKeyboardFocusable(className).toString()
                    props["IsOffscreen"] = (!isVisible).toString()

                    val node = ElementNode(
                        id = "hwnd_${handle}_d$depth",
                        name = title.ifBlank { className },
                        className = className,
                        bounds = Bounds(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top),
                        isEnabled = isEnabled,
                        isClickable = true,
                        depth = depth,
                        siblingIndex = siblingIdx++,
                        properties = props,
                        children = collectChildWindows(hwnd, depth + 1)
                    )
                    children.add(node)
                }
            } catch (_: Exception) {}
            true
        }, null)
        return children
    }

    private fun guessControlType(className: String): String = when {
        className.contains("Button", ignoreCase = true) -> "Button"
        className.contains("Edit", ignoreCase = true) -> "Edit"
        className.contains("Static", ignoreCase = true) -> "Text"
        className.contains("ListBox", ignoreCase = true) -> "List"
        className.contains("ComboBox", ignoreCase = true) -> "ComboBox"
        className.contains("TreeView", ignoreCase = true) -> "Tree"
        className.contains("ListView", ignoreCase = true) -> "DataGrid"
        className.contains("ScrollBar", ignoreCase = true) -> "ScrollBar"
        className.contains("ToolBar", ignoreCase = true) -> "ToolBar"
        className.contains("Tab", ignoreCase = true) -> "Tab"
        else -> "Pane"
    }

    private fun isKeyboardFocusable(className: String): Boolean =
        className.contains("Edit", ignoreCase = true) ||
        className.contains("Button", ignoreCase = true) ||
        className.contains("ComboBox", ignoreCase = true) ||
        className.contains("ListBox", ignoreCase = true)

    private fun captureRegion(x: Int, y: Int, w: Int, h: Int): ByteArray? {
        return try {
            val r = robot ?: return null
            val img: BufferedImage = r.createScreenCapture(Rectangle(x, y, w, h))
            val baos = ByteArrayOutputStream()
            ImageIO.write(img, "PNG", baos)
            baos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
