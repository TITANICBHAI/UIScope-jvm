package com.titanicbhai.uiscope.inspector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanicbhai.uiscope.model.ElementNode
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private fun findPath(roots: List<ElementNode>, targetId: String): List<ElementNode> {
    fun search(node: ElementNode, acc: List<ElementNode>): List<ElementNode>? {
        val path = acc + node
        if (node.id == targetId) return path
        node.children.forEach { child ->
            search(child, path)?.let { return it }
        }
        return null
    }
    roots.forEach { root ->
        search(root, emptyList())?.let { return it }
    }
    return emptyList()
}

private fun buildXPath(path: List<ElementNode>): String {
    if (path.isEmpty()) return ""
    val node = path.last()
    return when {
        !node.resourceId.isNullOrBlank() ->
            "//*[@resource-id='${node.resourceId}']"
        !node.text.isNullOrBlank() ->
            "//*[@text='${node.text}']"
        !node.contentDescription.isNullOrBlank() ->
            "//*[@content-desc='${node.contentDescription}']"
        else -> {
            path.joinToString("/") { n ->
                val cls = n.className.substringAfterLast('.')
                "${cls}[${n.siblingIndex + 1}]"
            }.let { "//$it" }
        }
    }
}

@Composable
fun BreadcrumbBar(
    rootNodes: List<ElementNode>,
    selectedNode: ElementNode?,
    onNodeSelected: (ElementNode) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    if (selectedNode == null) return

    val path = remember(rootNodes, selectedNode.id) {
        findPath(rootNodes, selectedNode.id)
    }
    val xpath = remember(path) { buildXPath(path) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500L)
            copied = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        path.forEachIndexed { index, node ->
            if (index > 0) {
                Text(
                    " › ",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
            val isLast = index == path.lastIndex
            Text(
                text = node.className.substringAfterLast('.'),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                color = if (isLast) colorScheme.primary else colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = !isLast) { onNodeSelected(node) }
            )
        }

        Spacer(Modifier.weight(1f))

        TextButton(
            onClick = {
                val sel = StringSelection(xpath)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
                copied = true
            },
            modifier = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                if (copied) "✓ Copied!" else "Copy as XPath",
                style = MaterialTheme.typography.labelSmall,
                color = if (copied) colorScheme.primary else colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}
