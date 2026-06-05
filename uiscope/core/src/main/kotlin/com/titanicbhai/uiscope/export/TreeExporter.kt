package com.titanicbhai.uiscope.export

import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementNode

object TreeExporter {

    fun toJson(nodes: List<ElementNode>, selectedOnly: ElementNode? = null): String {
        val targets = selectedOnly?.let { listOf(it) } ?: nodes
        return buildString {
            appendLine("[")
            targets.forEachIndexed { i, node ->
                append(nodeToJson(node, indent = 2))
                if (i < targets.size - 1) append(",")
                appendLine()
            }
            append("]")
        }
    }

    private fun nodeToJson(node: ElementNode, indent: Int): String {
        val pad = " ".repeat(indent)
        val childPad = " ".repeat(indent + 2)
        return buildString {
            appendLine("$pad{")
            appendLine("$childPad\"id\": ${jsonStr(node.id)},")
            appendLine("$childPad\"name\": ${jsonStr(node.name)},")
            appendLine("$childPad\"className\": ${jsonStr(node.className)},")
            appendLine("$childPad\"resourceId\": ${jsonStr(node.resourceId)},")
            appendLine("$childPad\"text\": ${jsonStr(node.text)},")
            appendLine("$childPad\"contentDescription\": ${jsonStr(node.contentDescription)},")
            appendLine("$childPad\"packageName\": ${jsonStr(node.packageName)},")
            appendLine("$childPad\"bounds\": ${boundsToJson(node.bounds)},")
            appendLine("$childPad\"isEnabled\": ${node.isEnabled},")
            appendLine("$childPad\"isClickable\": ${node.isClickable},")
            appendLine("$childPad\"isScrollable\": ${node.isScrollable},")
            appendLine("$childPad\"isFocused\": ${node.isFocused},")
            appendLine("$childPad\"isChecked\": ${node.isChecked},")
            appendLine("$childPad\"depth\": ${node.depth},")
            appendLine("$childPad\"siblingIndex\": ${node.siblingIndex},")
            if (node.children.isEmpty()) {
                appendLine("$childPad\"children\": []")
            } else {
                appendLine("$childPad\"children\": [")
                node.children.forEachIndexed { i, child ->
                    append(nodeToJson(child, indent + 4))
                    if (i < node.children.size - 1) append(",")
                    appendLine()
                }
                appendLine("$childPad]")
            }
            append("$pad}")
        }
    }

    private fun jsonStr(value: String?): String =
        if (value == null) "null"
        else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun boundsToJson(bounds: Bounds?): String =
        if (bounds == null) "null"
        else "{\"x\":${bounds.x},\"y\":${bounds.y},\"width\":${bounds.width},\"height\":${bounds.height}}"

    fun toXml(nodes: List<ElementNode>, selectedOnly: ElementNode? = null): String {
        val targets = selectedOnly?.let { listOf(it) } ?: nodes
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<hierarchy>")
            targets.forEach { node ->
                append(nodeToXml(node, indent = 2))
            }
            appendLine("</hierarchy>")
        }
    }

    private fun nodeToXml(node: ElementNode, indent: Int): String {
        val pad = " ".repeat(indent)
        return buildString {
            val attrs = buildString {
                append(" class=\"${escapeXml(node.className)}\"")
                node.resourceId?.let { append(" resource-id=\"${escapeXml(it)}\"") }
                node.text?.let { append(" text=\"${escapeXml(it)}\"") }
                node.contentDescription?.let { append(" content-desc=\"${escapeXml(it)}\"") }
                node.packageName?.let { append(" package=\"${escapeXml(it)}\"") }
                node.bounds?.let { b ->
                    append(" bounds=\"[${b.x},${b.y}][${b.x + b.width},${b.y + b.height}]\"")
                }
                append(" enabled=\"${node.isEnabled}\"")
                append(" clickable=\"${node.isClickable}\"")
                append(" scrollable=\"${node.isScrollable}\"")
                append(" focused=\"${node.isFocused}\"")
                node.isChecked?.let { append(" checked=\"$it\"") }
                append(" depth=\"${node.depth}\"")
                append(" index=\"${node.siblingIndex}\"")
            }
            if (node.children.isEmpty()) {
                appendLine("$pad<node$attrs />")
            } else {
                appendLine("$pad<node$attrs>")
                node.children.forEach { child ->
                    append(nodeToXml(child, indent + 2))
                }
                appendLine("$pad</node>")
            }
        }
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    fun toOutline(nodes: List<ElementNode>, selectedOnly: ElementNode? = null): String {
        val targets = selectedOnly?.let { listOf(it) } ?: nodes
        return buildString {
            targets.forEach { node ->
                append(nodeToOutline(node, 0))
            }
        }
    }

    private fun nodeToOutline(node: ElementNode, depth: Int): String {
        val indent = "  ".repeat(depth)
        val marker = if (node.children.isNotEmpty()) "▸" else "•"
        val label = when {
            !node.text.isNullOrBlank() -> "\"${node.text}\""
            !node.contentDescription.isNullOrBlank() -> "[${node.contentDescription}]"
            else -> node.className.substringAfterLast('.')
        }
        val resourcePart = node.resourceId?.let { " @${it.substringAfterLast('/')}" } ?: ""
        val boundsPart = node.bounds?.let { b -> "  [${b.x},${b.y} ${b.width}×${b.height}]" } ?: ""
        return buildString {
            appendLine("$indent$marker $label$resourcePart$boundsPart")
            node.children.forEach { child ->
                append(nodeToOutline(child, depth + 1))
            }
        }
    }
}
