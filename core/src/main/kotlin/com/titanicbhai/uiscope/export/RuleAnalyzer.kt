package com.titanicbhai.uiscope.export

import com.titanicbhai.uiscope.model.ElementNode

enum class SelectorTier { STRONG, MEDIUM, WEAK }

data class RuleRecommendation(
    val selectorType: String,
    val selector: Map<String, String>,
    val confidence: Int,
    val tier: SelectorTier,
    val stability: Float,
    val reasons: List<String>,
    val warnings: List<String>,
    val isFragile: Boolean
)

data class RuleQualitySummary(
    val strongCount: Int,
    val mediumCount: Int,
    val weakCount: Int,
    val exportableCount: Int,
    val averageConfidence: Float
)

object RuleAnalyzer {

    fun analyze(node: ElementNode, allNodes: List<ElementNode> = emptyList()): RuleRecommendation {
        var score = 0
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val selector = mutableMapOf<String, String>()

        val resId = node.resourceId
        val text = node.text?.takeIf { it.isNotBlank() }
        val contentDesc = node.contentDescription?.takeIf { it.isNotBlank() }

        val isResIdObfuscated = resId != null && (resId.matches(Regex(".*:id/[a-z]{1,3}\\d*$"))
                || resId.contains(":id/") && resId.substringAfterLast("/").length <= 2)
        val hasStableResId = resId != null && !resId.isNullOrBlank() && !isResIdObfuscated

        val nodesWithSameResId = if (hasStableResId && allNodes.isNotEmpty())
            allNodes.count { it.resourceId == resId } else 0

        var selectorType: String

        when {
            hasStableResId -> {
                selectorType = "Resource ID"
                selector["resourceId"] = resId!!
                if (nodesWithSameResId <= 1) {
                    score += 48
                    reasons.add("Unique resource ID — highly reliable selector")
                } else {
                    score += 34
                    reasons.add("Resource ID present but shared by $nodesWithSameResId nodes — add index")
                }
            }
            text != null -> {
                selectorType = "Text"
                selector["text"] = text
                val nodesWithSameText = allNodes.count { it.text == text }
                if (nodesWithSameText <= 1) {
                    score += 24
                    reasons.add("Unique text label found")
                } else {
                    score += 14
                    reasons.add("Text label shared by $nodesWithSameText nodes — fragile if duplicated")
                }
            }
            contentDesc != null -> {
                selectorType = "Content Description"
                selector["contentDescription"] = contentDesc
                score += 20
                reasons.add("Content description used as selector")
            }
            else -> {
                selectorType = "Class + Index"
                selector["className"] = node.className
                selector["index"] = node.siblingIndex.toString()
                score += 8
                warnings.add("No stable ID or text — selector is fragile and may break on app updates")
            }
        }

        if (isResIdObfuscated) {
            warnings.add("Resource ID appears obfuscated (ProGuard/R8) — may change between app versions")
        }

        if (node.isClickable) {
            score += 8
            reasons.add("Element is interactive (clickable)")
        }

        if (node.isEnabled) {
            score += 4
        }

        if (allNodes.isEmpty()) {
            score = (score * 0.85f).toInt()
        }

        val stability = when {
            hasStableResId && nodesWithSameResId <= 1 -> 0.95f
            hasStableResId -> 0.75f
            text != null -> 0.65f
            else -> 0.30f
        }

        val tier = when {
            score >= 80 -> SelectorTier.STRONG
            score >= 55 -> SelectorTier.MEDIUM
            else -> SelectorTier.WEAK
        }

        val isFragile = tier == SelectorTier.WEAK || isResIdObfuscated || selectorType == "Class + Index"

        return RuleRecommendation(
            selectorType = selectorType,
            selector = selector,
            confidence = score.coerceIn(0, 100),
            tier = tier,
            stability = stability,
            reasons = reasons,
            warnings = warnings,
            isFragile = isFragile
        )
    }

    fun summarize(recommendations: List<RuleRecommendation>): RuleQualitySummary {
        val strong = recommendations.count { it.tier == SelectorTier.STRONG }
        val medium = recommendations.count { it.tier == SelectorTier.MEDIUM }
        val weak = recommendations.count { it.tier == SelectorTier.WEAK }
        val exportable = recommendations.count { it.tier != SelectorTier.WEAK }
        val avgConf = if (recommendations.isEmpty()) 0f
        else recommendations.map { it.confidence }.average().toFloat()
        return RuleQualitySummary(strong, medium, weak, exportable, avgConf)
    }

    fun matchesSearch(query: String, node: ElementNode): Boolean {
        if (query.isBlank()) return true
        val q = query.lowercase()
        val fields = listOfNotNull(
            node.resourceId, node.text, node.contentDescription,
            node.className, node.name, node.packageName
        )
        return fields.any { it.lowercase().contains(q) }
    }
}
