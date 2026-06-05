package com.titanicbhai.uiscope.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanicbhai.uiscope.model.ElementNode

@Composable
fun TreePanel(
    nodes: List<ElementNode>,
    selectedNode: ElementNode?,
    onNodeSelected: (ElementNode) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Element Tree",
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.4f))

        if (nodes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "No elements",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Select a target to begin inspection",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    )
                }
            }
        } else {
            val flatNodes = remember(nodes) { flattenTree(nodes) }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(flatNodes, key = { it.id }) { node ->
                    TreeNodeRow(
                        node = node,
                        isSelected = node.id == selectedNode?.id,
                        onClick = { onNodeSelected(node) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TreeNodeRow(
    node: ElementNode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val indentPx = (node.depth * 14).dp
    val hasChildren = node.children.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) colorScheme.primaryContainer
                else colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(start = 12.dp + indentPx, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (hasChildren) "▸ " else "  ",
            fontSize = 9.sp,
            color = if (isSelected) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f)) {
            val displayName = when {
                node.name.isNotBlank() -> node.name
                else -> node.className.substringAfterLast('.')
            }
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (isSelected) colorScheme.onPrimaryContainer else colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            node.resourceId?.takeIf { it.isNotBlank() }?.let { id ->
                Text(
                    text = id,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (isSelected) colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    else colorScheme.primary.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun flattenTree(nodes: List<ElementNode>): List<ElementNode> {
    val result = mutableListOf<ElementNode>()
    fun visit(node: ElementNode) {
        result.add(node)
        node.children.forEach { visit(it) }
    }
    nodes.forEach { visit(it) }
    return result
}
