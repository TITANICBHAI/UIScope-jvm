package com.titanicbhai.uiscope.export

import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.Target

/**
 * Exports a [Capture] as valid XML 1.0.
 *
 * - Characters XML 1.0 cannot represent (anything outside `#x9 | #xA | #xD | #x20–#xD7FF | #xE000–#xFFFD | #x10000–#x10FFFF`,
 *   including unpaired surrogates) are dropped.
 * - `& < > " '` are escaped; tab, line feed and carriage return inside attribute values become character references, so the
 *   value survives a round trip through any XML parser (fixes B4's cousin for XML).
 * - Android captures use UIAutomator's real attribute set and the `<hierarchy rotation="…">` root, so tools that read
 *   `uiautomator dump` files can read the export. `rotation` comes from [com.titanicbhai.uiscope.model.ScreenInfo.rotation]; when a node
 *   has them, `hint`, `drawing-order` and `display-id` are written too.
 * - Windows captures use `<hierarchy platform="windows" …>` with UI Automation names (`automation-id`, `control-type`).
 */
object XmlExporter {
    private const val DECLARATION = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
    private const val INDENT = "  "

    fun export(capture: Capture): String {
        val out = StringBuilder(DECLARATION).append('\n')
        val windows = capture.target is Target.Windows
        if (windows) {
            val window = (capture.target as Target.Windows).window
            out.append("<hierarchy platform=\"windows\" window=\"").append(attr(window.title))
                .append("\" process=\"").append(attr(window.processName)).append("\">\n")
        } else {
            out.append("<hierarchy rotation=\"").append(capture.screen.rotation).append("\">\n")
        }
        capture.roots.forEach { writeNode(out, it, 1, windows) }
        out.append("</hierarchy>\n")
        return out.toString()
    }

    private fun writeNode(out: StringBuilder, node: ElementNode, depth: Int, windows: Boolean) {
        val pad = INDENT.repeat(depth)
        out.append(pad).append("<node")
        val index = node.properties["index"] ?: node.path.siblingIndex.toString()
        if (windows) windowsAttributes(out, node, index) else androidAttributes(out, node, index)
        if (node.children.isEmpty()) {
            out.append(" />\n")
        } else {
            out.append(">\n")
            node.children.forEach { writeNode(out, it, depth + 1, windows) }
            out.append(pad).append("</node>\n")
        }
    }

    private fun androidAttributes(out: StringBuilder, node: ElementNode, index: String) {
        val f = node.flags
        put(out, "index", index)
        put(out, "text", node.text.orEmpty())
        put(out, "resource-id", node.identifier.orEmpty())
        put(out, "class", node.className)
        put(out, "package", node.properties["package"].orEmpty())
        put(out, "content-desc", node.contentDescription.orEmpty())
        put(out, "checkable", f.checkable)
        put(out, "checked", f.checked == true)
        put(out, "clickable", f.clickable)
        put(out, "enabled", f.enabled)
        put(out, "focusable", f.focusable)
        put(out, "focused", f.focused)
        put(out, "scrollable", f.scrollable)
        put(out, "long-clickable", f.longClickable)
        put(out, "password", f.password)
        put(out, "selected", f.selected)
        put(out, "bounds", boundsText(node.bounds))
        for (extra in listOf("hint", "drawing-order", "display-id")) node.properties[extra]?.let { put(out, extra, it) }
    }

    private fun windowsAttributes(out: StringBuilder, node: ElementNode, index: String) {
        val f = node.flags
        put(out, "index", index)
        put(out, "name", node.name.orEmpty())
        put(out, "automation-id", node.identifier.orEmpty())
        put(out, "class", node.className)
        put(out, "control-type", node.properties["ControlType"].orEmpty())
        put(out, "text", node.text.orEmpty())
        put(out, "enabled", f.enabled)
        put(out, "offscreen", f.offscreen)
        put(out, "focusable", f.focusable)
        put(out, "focused", f.focused)
        put(out, "password", f.password)
        put(out, "bounds", boundsText(node.bounds))
    }

    private fun boundsText(b: Bounds) = "[${b.x},${b.y}][${b.right},${b.bottom}]"

    private fun put(out: StringBuilder, name: String, value: Boolean) = put(out, name, value.toString())

    private fun put(out: StringBuilder, name: String, value: String) {
        out.append(' ').append(name).append("=\"").append(attr(value)).append('"')
    }

    private fun attr(value: String): String = escapeAttribute(stripInvalidXmlChars(value))

    /** Removes every character XML 1.0 forbids (see class comment). Valid surrogate pairs are kept as one character. */
    fun stripInvalidXmlChars(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isXmlChar(cp)) out.appendCodePoint(cp)
        }
        return out.toString()
    }

    private fun isXmlChar(cp: Int): Boolean =
        cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF

    /** Escapes a value for use inside a double-quoted XML attribute. */
    fun escapeAttribute(text: String): String {
        val out = StringBuilder(text.length + 8)
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                '\n' -> out.append("&#10;")
                '\r' -> out.append("&#13;")
                '\t' -> out.append("&#9;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
