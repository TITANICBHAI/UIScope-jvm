package com.titanicbhai.uiscope.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanicbhai.uiscope.model.ElementNode

private data class FlatNode(
    val node: ElementNode,
    val hasChildren: Boolean,
    val isCollapsed: Boolean
)

private fun countAll(nodes: List<ElementNode>): Int {
    var count = 0
    fun visit(n: ElementNode) { count++; n.children.forEach { visit(it) } }
    nodes.forEach { visit(it) }
    return count
}

private fun collectDepthGe(nodes: List<ElementNode>, minDepth: Int): Set<String> {
    val ids = mutableSetOf<String>()
    fun visit(n: ElementNode) {
        if (n.depth >= minDepth && n.children.isNotEmpty()) ids.add(n.id)
        n.children.forEach { visit(it) }
    }
    nodes.forEach { visit(it) }
    return ids
}

private fun buildVisibleList(nodes: List<ElementNode>, collapsedIds: Set<String>): List<FlatNode> {
    val result = mutableListOf<FlatNode>()
    fun visit(node: ElementNode) {
        val hasChildren = node.children.isNotEmpty()
        val isCollapsed = node.id in collapsedIds
        result.add(FlatNode(node, hasChildren, isCollapsed))
        if (!isCollapsed) node.children.forEach { visit(it) }
    }
    nodes.forEach { visit(it) }
    return result
}

@Composable
fun TreePanel(
    rootNodes: List<ElementNode>,
    selectedNode: ElementNode?,
    onNodeSelected: (ElementNode) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val total = remember(rootNodes) { countAll(rootNodes) }
    var collapsedIds by remember(rootNodes) {
        mutableStateOf(if (total > 1000) collectDepthGe(rootNodes, 3) else emptySet())
    }
    val visibleList = remember(rootNodes, collapsedIds) { buildVisibleList(rootNodes, collapsedIds) }
    val listState = rememberLazyListState()

    LaunchedEffect(selectedNode) {
        val idx = visibleList.indexOfFirst { it.node.id == selectedNode?.id }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Element Tree", style = MaterialTheme.typography.labelLarge, color = colorScheme.onSurfaceVariant)
                if (total > 0) {
                    Text(
                        "$total nodes",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.4f))

        if (rootNodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No elements", style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurfaceVariant)
                    Text(
                        "Select a target to begin inspection",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                items(visibleList, key = { it.node.id }) { flatNode ->
                    TreeNodeRow(
                        flatNode = flatNode,
                        isSelected = flatNode.node.id == selectedNode?.id,
                        onToggleCollapse = {
                            collapsedIds = if (flatNode.node.id in collapsedIds)
                                collapsedIds - flatNode.node.id
                            else
                                collapsedIds + flatNode.node.id
                        },
                        onClick = { onNodeSelected(flatNode.node) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TreeNodeRow(
    flatNode: FlatNode,
    isSelected: Boolean,
    onToggleCollapse: () -> Unit,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val node = flatNode.node
    val indentDp = (node.depth * 12).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    isSelected -> colorScheme.primaryContainer
                    else -> colorScheme.surface
                }
            )
            .clickable(onClick = onClick)
            .padding(start = 8.dp + indentDp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (flatNode.hasChildren) {
            Text(
                text = if (flatNode.isCollapsed) "▶" else "▼",
                fontSize = 8.sp,
                color = if (isSelected) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onToggleCollapse)
                    .padding(end = 4.dp, start = 2.dp)
                    .size(14.dp)
                    .wrapContentSize(Alignment.Center)
            )
        } else {
            Spacer(Modifier.width(18.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            val shortClass = node.className.substringAfterLast('.')
            val nodeText = node.text
            val nodeContentDesc = node.contentDescription
            val nodeResourceId = node.resourceId
            val displayName = when {
                !nodeText.isNullOrBlank() -> "\"${nodeText.take(40)}\""
                !nodeContentDesc.isNullOrBlank() -> "[${nodeContentDesc.take(40)}]"
                else -> shortClass
            }
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = if (isSelected) colorScheme.onPrimaryContainer else colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subLabel = when {
                nodeResourceId?.isNotBlank() == true ->
                    "@${nodeResourceId.substringAfterLast('/')}"
                nodeText.isNullOrBlank() && nodeContentDesc.isNullOrBlank() -> null
                else -> shortClass
            }
            subLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                    color = if (isSelected) colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else colorScheme.primary.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
