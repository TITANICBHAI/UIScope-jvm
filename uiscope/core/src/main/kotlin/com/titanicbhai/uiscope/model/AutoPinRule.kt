package com.titanicbhai.uiscope.model

import java.util.UUID

enum class MatchField { RES_ID, TEXT, CLASS, DESC }

data class AutoPinRule(
    val id: String = UUID.randomUUID().toString(),
    val pattern: String,
    val matchField: MatchField,
    val enabled: Boolean = true,
    val label: String = ""
) {
    fun matches(node: ElementNode): Boolean {
        if (!enabled) return false
        val value = when (matchField) {
            MatchField.RES_ID -> node.resourceId ?: return false
            MatchField.TEXT -> node.text ?: return false
            MatchField.CLASS -> node.className
            MatchField.DESC -> node.contentDescription ?: return false
        }
        return globMatch(pattern, value)
    }
}

fun globMatch(pattern: String, value: String): Boolean {
    val p = pattern.lowercase()
    val v = value.lowercase()
    if (!p.contains('*')) return p == v
    val parts = p.split('*')
    var pos = 0
    for (i in parts.indices) {
        val part = parts[i]
        if (part.isEmpty()) continue
        val idx = v.indexOf(part, pos)
        if (idx == -1) return false
        if (i == 0 && idx != 0) return false
        pos = idx + part.length
    }
    if (!p.endsWith('*') && parts.last().isNotEmpty()) {
        return v.endsWith(parts.last())
    }
    return true
}
