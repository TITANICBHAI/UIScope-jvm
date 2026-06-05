package com.titanicbhai.uiscope.inspector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.InspectionMode

@Composable
fun VisualCanvas(
    screenshot: ImageBitmap?,
    selectedNode: ElementNode?,
    mode: InspectionMode,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val highlightColor = if (mode == InspectionMode.ANDROID)
        androidx.compose.ui.graphics.Color(0xFFE53935)
    else
        androidx.compose.ui.graphics.Color(0xFF1A6EC7)

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (screenshot == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (mode == InspectionMode.PC) "🖥️" else "📱",
                    style = MaterialTheme.typography.displayMedium
                )
                Text(
                    text = if (mode == InspectionMode.PC)
                        "Press Pick (Alt+Shift+P) to select an element"
                    else
                        "Select a connected device to begin",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (mode == InspectionMode.PC)
                        "Or press R to refresh after making a selection"
                    else
                        "Plug in your phone or enter a wireless ADB address",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                )
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                val canvasWidth = constraints.maxWidth.toFloat()
                val canvasHeight = constraints.maxHeight.toFloat()
                val imgWidth = screenshot.width.toFloat()
                val imgHeight = screenshot.height.toFloat()
                val scale = minOf(canvasWidth / imgWidth, canvasHeight / imgHeight)
                val offsetX = (canvasWidth - imgWidth * scale) / 2f
                val offsetY = (canvasHeight - imgHeight * scale) / 2f

                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawImage(screenshot)

                    selectedNode?.bounds?.let { bounds ->
                        val left = offsetX + bounds.x * scale
                        val top = offsetY + bounds.y * scale
                        val right = left + bounds.width * scale
                        val bottom = top + bounds.height * scale

                        drawRect(
                            color = highlightColor,
                            topLeft = androidx.compose.ui.geometry.Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                            style = Stroke(width = 3f)
                        )
                        drawRect(
                            color = highlightColor.copy(alpha = 0.08f),
                            topLeft = androidx.compose.ui.geometry.Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(right - left, bottom - top)
                        )
                    }
                }
            }
        }
    }
}
