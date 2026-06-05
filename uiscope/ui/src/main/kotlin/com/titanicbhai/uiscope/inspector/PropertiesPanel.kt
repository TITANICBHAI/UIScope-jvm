package com.titanicbhai.uiscope.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.model.ElementNode

@Composable
fun PropertiesPanel(node: ElementNode?) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Properties",
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurfaceVariant
            )
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
            val props = buildProperties(node)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(props) { (key, value) ->
                    PropertyRow(key = key, value = value)
                }
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

private fun buildProperties(node: ElementNode): List<Pair<String, String?>> = buildList {
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
