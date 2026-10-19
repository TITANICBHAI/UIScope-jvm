package com.titanicbhai.uiscope.model

import java.time.Instant

/** A device or a window that was captured. */
sealed interface Target {
    data class Android(val device: DeviceInfo, val foregroundPackage: String? = null) : Target

    data class Windows(val window: WindowInfo) : Target
}

data class DeviceInfo(
    val serial: String,
    val model: String? = null,
    val androidVersion: String? = null,
    val sdkInt: Int? = null,
)

data class WindowInfo(
    val title: String,
    val processName: String,
    val pid: Long? = null,
    /** UI Automation FrameworkId: Win32, WPF, XAML, Chrome, … */
    val frameworkId: String? = null,
    val isElevated: Boolean = false,
    /** Native window handle as hex text, used to re-capture the same window. */
    val hwnd: String? = null,
)

/**
 * Size of the captured surface and where its top-left corner sits in capture space.
 * Android: origin (0,0). Windows: the window's position on the virtual screen, so absolute element bounds need the origin subtracted
 * before they are drawn on the screenshot (that missing subtraction was bug B3).
 */
data class ScreenInfo(
    val width: Int,
    val height: Int,
    val originX: Int = 0,
    val originY: Int = 0,
)

/** A screenshot belonging to a capture. [file] is a path (absolute for a live capture, relative inside a snapshot folder). */
data class Screenshot(val file: String, val width: Int, val height: Int)

/** A non-fatal problem found while capturing (for example `TREE_TRUNCATED`); [code] is a stable identifier from Appendix A. */
data class CaptureWarning(val code: String, val detail: String? = null)

/** One immutable snapshot of a target's UI: what the inspector shows, searches, exports and saves. */
data class Capture(
    val id: String,
    val takenAt: Instant,
    val target: Target,
    val screen: ScreenInfo,
    val screenshot: Screenshot?,
    val roots: List<ElementNode>,
    val warnings: List<CaptureWarning> = emptyList(),
)
