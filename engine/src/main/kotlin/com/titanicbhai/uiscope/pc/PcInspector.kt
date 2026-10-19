package com.titanicbhai.uiscope.pc

import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementNode

data class PermissionInstructions(
    val os: String,
    val title: String,
    val steps: List<String>,
    val actionLabel: String?,
    val actionUrl: String?
)

data class PcWindowInfo(
    val handle: Long,
    val title: String,
    val bounds: Bounds
)

interface PcInspector {
    fun isPermissionGranted(): Boolean
    fun getPermissionInstructions(): PermissionInstructions?
    fun findElementAtPoint(screenX: Int, screenY: Int): ElementNode?
    fun getRootTree(windowHandle: Long): List<ElementNode>
    fun captureWindowScreenshot(windowHandle: Long): ByteArray?
    fun getWindowHandleAt(screenX: Int, screenY: Int): Long
    fun getWindowInfo(handle: Long): PcWindowInfo?
    fun startEventSubscription(onEvent: () -> Unit)
    fun stopEventSubscription()
    fun dispose()
}

object PcInspectorFactory {
    fun create(): PcInspector {
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            os.contains("windows") -> WindowsUiAutomation()
            os.contains("mac") -> MacOsAccessibility()
            else -> LinuxAtSpi()
        }
    }

    val currentOs: OsKind
        get() {
            val os = System.getProperty("os.name", "").lowercase()
            return when {
                os.contains("windows") -> OsKind.WINDOWS
                os.contains("mac") -> OsKind.MACOS
                else -> OsKind.LINUX
            }
        }
}

enum class OsKind { WINDOWS, MACOS, LINUX }
