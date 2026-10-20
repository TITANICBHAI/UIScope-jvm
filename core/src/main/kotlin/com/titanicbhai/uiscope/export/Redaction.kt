package com.titanicbhai.uiscope.export

import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.Target

const val REDACTED = "[redacted]"

/** Property keys that describe structure, not content. Every other property value is treated as text-bearing and redacted. */
private val STRUCTURAL_PROPERTIES = setOf(
    "package", "index", "ControlType", "LocalizedControlType", "FrameworkId", "ProcessId", "Hwnd", "RuntimeId", "Patterns", "rotation",
)

/**
 * Returns a copy with every text-bearing field replaced by `[redacted]`: element text, content description, Windows Name, all
 * non-structural property values, and the Windows window title. Fields that are absent stay absent. Structure, classes,
 * identifiers, bounds and flags are kept so the export is still useful for layout bug reports.
 *
 * A screenshot cannot be redacted; the UI warns when one is included.
 */
fun Capture.redacted(): Capture = copy(
    target = when (val t = target) {
        is Target.Windows -> Target.Windows(t.window.copy(title = REDACTED))
        is Target.Android -> t
    },
    roots = roots.map(::redactNode),
)

private fun redactNode(node: ElementNode): ElementNode = node.copy(
    name = node.name?.let { REDACTED },
    text = node.text?.let { REDACTED },
    contentDescription = node.contentDescription?.let { REDACTED },
    properties = node.properties.mapValues { (key, value) -> if (key in STRUCTURAL_PROPERTIES) value else REDACTED },
    children = node.children.map(::redactNode),
)
