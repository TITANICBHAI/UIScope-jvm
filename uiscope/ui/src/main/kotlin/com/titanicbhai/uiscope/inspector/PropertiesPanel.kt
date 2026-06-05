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
import com.titanicbhai.uiscope.model.InspectionMode

@Composable
fun PropertiesPanel(node: ElementNode?, mode: InspectionMode = InspectionMode.ANDROID) {
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
            val props = if (mode == InspectionMode.PC) buildPcProperties(node) else buildAndroidProperties(node)
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
    // Show remaining extra properties
    val knownKeys = setOf(
        "AutomationId", "ControlType", "Handle", "IsKeyboardFocusable",
        "IsOffscreen", "IsVisible", "Role", "Subrole", "Application",
        "BundleIdentifier", "WindowId"
    )
    node.properties.filterKeys { it !in knownKeys }.forEach { (k, v) -> add(k to v) }
}
