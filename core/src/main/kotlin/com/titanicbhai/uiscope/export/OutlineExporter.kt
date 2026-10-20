package com.titanicbhai.uiscope.export

import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementNode

/**
 * Plain-text outline, one element per line: `indent Class "label" #id [x,y,w,h]`.
 *
 * - indent = two spaces per tree level; `Class` is the simple class name (text after the last `.`);
 * - `"label"` is the text, else the Windows Name, else the content description (omitted when none), with `\`, `"` and control
 *   characters escaped so every element stays on one line;
 * - `#id` is the identifier (omitted when none); bounds are `[x,y,width,height]` in capture space.
 */
object OutlineExporter {
    fun export(capture: Capture): String {
        val out = StringBuilder()
        // Iterative on purpose: very deep trees must not overflow the stack.
        val pending = ArrayDeque<Pair<ElementNode, Int>>()
        capture.roots.asReversed().forEach { pending.addLast(it to 0) }
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            line(out, node, depth)
            node.children.asReversed().forEach { pending.addLast(it to depth + 1) }
        }
        return out.toString()
    }

    private fun line(out: StringBuilder, node: ElementNode, depth: Int) {
        out.append("  ".repeat(depth)).append(node.className.substringAfterLast('.'))
        (node.text ?: node.name ?: node.contentDescription)?.let { out.append(" \"").append(escapeLabel(it)).append('"') }
        node.identifier?.let { out.append(" #").append(escapeLabel(it)) }
        val b = node.bounds
        out.append(" [").append(b.x).append(',').append(b.y).append(',').append(b.width).append(',').append(b.height).append("]\n")
    }

    private fun escapeLabel(text: String): String {
        val out = StringBuilder(text.length + 4)
        for (c in text) {
            when {
                c == '\\' -> out.append("\\\\")
                c == '"' -> out.append("\\\"")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' || c == '\u007f' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
