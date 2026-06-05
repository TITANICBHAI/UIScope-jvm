package com.titanicbhai.uiscope.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanicbhai.uiscope.export.RuleAnalyzer
import com.titanicbhai.uiscope.export.RuleRecommendation
import com.titanicbhai.uiscope.export.SelectorTier
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.InspectionMode
import com.titanicbhai.uiscope.theme.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun PropertiesPanel(
    node: ElementNode?,
    mode: InspectionMode = InspectionMode.ANDROID,
    isBookmarked: Boolean = false,
    onBookmark: ((ElementNode) -> Unit)? = null,
    onRemoveBookmark: ((ElementNode) -> Unit)? = null,
    allNodes: List<ElementNode> = emptyList()
) {
    val colorScheme = MaterialTheme.colorScheme

    val recommendation = remember(node, allNodes) {
        node?.let { RuleAnalyzer.analyze(it, allNodes) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Properties",
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurfaceVariant
            )
            if (node != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    recommendation?.let { rec ->
                        ConfidenceBadge(rec)
                    }
                    if (onBookmark != null || onRemoveBookmark != null) {
                        TextButton(
                            onClick = {
                                if (isBookmarked) onRemoveBookmark?.invoke(node)
                                else onBookmark?.invoke(node)
                            },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (isBookmarked) "📌 Pinned" else "📌 Pin",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp,
                                color = if (isBookmarked) AccentGreen else colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.4f))

        if (node == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Select an element to view its properties",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                )
            }
        } else {
            val props = if (mode == InspectionMode.PC) buildPcProperties(node) else buildAndroidProperties(node)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(props) { (key, value) ->
                    PropertyRow(key = key, value = value)
                }

                if (recommendation != null) {
                    item {
                        RecommendationCard(recommendation = recommendation)
                    }
                }
            }
        }
    }
}

@Composable
fun ConfidenceBadge(recommendation: RuleRecommendation) {
    val color = tierColor(recommendation.confidence)
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = "${recommendation.confidence} ${recommendation.tier.name}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            fontSize = 10.sp
        )
    }
}

@Composable
fun QualityPill(label: String, count: Int, color: Color) {
    if (count == 0) return
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "$label $count",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun RecommendationCard(recommendation: RuleRecommendation) {
    val colorScheme = MaterialTheme.colorScheme
    val color = tierColor(recommendation.confidence)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Selector Quality",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = colorScheme.onSurfaceVariant
            )
            ConfidenceBadge(recommendation)
        }

        Spacer(Modifier.height(8.dp))

        // Selector type + value
        Text(
            "Type: ${recommendation.selectorType}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
        recommendation.selector.forEach { (k, v) ->
            val selectorStr = "$k = \"$v\""
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    selectorStr,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                    color = color,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = {
                        val sel = StringSelection(v)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
                    },
                    modifier = Modifier.height(22.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text("Copy", fontSize = 9.sp, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Stability
        Text(
            "Stability: ${(recommendation.stability * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            fontSize = 10.sp
        )

        // Reasons
        if (recommendation.reasons.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            recommendation.reasons.forEach { reason ->
                Text(
                    "• $reason",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentGreen,
                    fontSize = 10.sp
                )
            }
        }

        // Warnings
        if (recommendation.warnings.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            recommendation.warnings.forEach { warning ->
                Text(
                    "⚠ $warning",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentOrange,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun PropertyRow(key: String, value: String?) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value ?: "(null)",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (value != null) colorScheme.onSurface else colorScheme.onSurface.copy(alpha = 0.35f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        HorizontalDivider(
            modifier = Modifier.padding(top = 5.dp),
            color = colorScheme.outline.copy(alpha = 0.18f)
        )
    }
}

private fun buildAndroidProperties(node: ElementNode): List<Pair<String, String?>> = buildList {
    add("Name" to node.name.takeIf { it.isNotBlank() })
    add("Class" to node.className)
    add("Resource ID" to node.resourceId)
    add("Text" to node.text)
    add("Content Description" to node.contentDescription)
    add("Package" to node.packageName)
    node.bounds?.let { b -> add("Bounds" to "[${b.x}, ${b.y}] ${b.width}×${b.height}") }
    add("Enabled" to node.isEnabled.toString())
    add("Clickable" to node.isClickable.toString())
    add("Scrollable" to node.isScrollable.toString())
    add("Focused" to node.isFocused.toString())
    node.isChecked?.let { add("Checked" to it.toString()) }
    add("Depth" to node.depth.toString())
    add("Sibling Index" to node.siblingIndex.toString())
    node.properties.forEach { (k, v) -> add(k to v) }
}

private fun buildPcProperties(node: ElementNode): List<Pair<String, String?>> = buildList {
    add("Name" to node.name.takeIf { it.isNotBlank() })
    add("ClassName" to node.className)
    node.properties["AutomationId"]?.let { add("AutomationId" to it) }
    node.properties["ControlType"]?.let { add("ControlType" to it) }
    node.properties["Handle"]?.let { add("Handle" to it) }
    node.bounds?.let { b ->
        add("Bounds (x, y)" to "${b.x}, ${b.y}")
        add("Bounds (w, h)" to "${b.width} × ${b.height}")
    }
    add("IsEnabled" to node.isEnabled.toString())
    node.properties["IsKeyboardFocusable"]?.let { add("IsKeyboardFocusable" to it) }
    node.properties["IsOffscreen"]?.let { add("IsOffscreen" to it) }
    node.properties["IsVisible"]?.let { add("IsVisible" to it) }
    add("IsFocused" to node.isFocused.toString())
    node.properties["Role"]?.let { add("Role" to it) }
    node.properties["Subrole"]?.let { add("Subrole" to it) }
    node.properties["Application"]?.let { add("Application" to it) }
    node.properties["BundleIdentifier"]?.let { add("BundleIdentifier" to it) }
    node.properties["WindowId"]?.let { add("WindowId" to it) }
    add("Depth" to node.depth.toString())
    add("Sibling Index" to node.siblingIndex.toString())
    val knownKeys = setOf(
        "AutomationId", "ControlType", "Handle", "IsKeyboardFocusable",
        "IsOffscreen", "IsVisible", "Role", "Subrole", "Application",
        "BundleIdentifier", "WindowId"
    )
    node.properties.filterKeys { it !in knownKeys }.forEach { (k, v) -> add(k to v) }
}
