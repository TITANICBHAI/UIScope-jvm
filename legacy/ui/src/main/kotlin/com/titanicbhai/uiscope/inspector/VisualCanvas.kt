@file:OptIn(ExperimentalComposeUiApi::class)

package com.titanicbhai.uiscope.inspector

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.*
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.InspectionMode
import com.titanicbhai.uiscope.theme.AccentGreen
import com.titanicbhai.uiscope.theme.AccentRed
import com.titanicbhai.uiscope.theme.AccentBlue
import com.titanicbhai.uiscope.theme.NodeSelectedBorder
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import kotlin.math.roundToInt

private fun flattenAll(nodes: List<ElementNode>): List<ElementNode> {
    val result = mutableListOf<ElementNode>()
    fun visit(n: ElementNode) { result.add(n); n.children.forEach { visit(it) } }
    nodes.forEach { visit(it) }
    return result
}

private fun hitTest(flatNodes: List<ElementNode>, imgX: Float, imgY: Float): ElementNode? =
    flatNodes
        .filter { n ->
            n.bounds?.let { b ->
                imgX >= b.x && imgX <= b.x + b.width && imgY >= b.y && imgY <= b.y + b.height
            } ?: false
        }
        .maxByOrNull { it.depth }

private fun copyText(text: String) {
    val sel = StringSelection(text)
    Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
}

@Composable
fun VisualCanvas(
    screenshot: ImageBitmap?,
    rootNodes: List<ElementNode>,
    selectedNode: ElementNode?,
    onNodeSelected: (ElementNode) -> Unit,
    mode: InspectionMode,
    bookmarkedNodeIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val highlightColor = if (mode == InspectionMode.ANDROID) AccentRed else AccentBlue

    var zoomScale by remember(screenshot) { mutableStateOf(1f) }
    var panOffset by remember(screenshot) { mutableStateOf(Offset.Zero) }
    var hoveredNode by remember { mutableStateOf<ElementNode?>(null) }
    var mousePos by remember { mutableStateOf(Offset.Zero) }
    var showContextMenu by remember { mutableStateOf(false) }
    var lastClickTime by remember { mutableStateOf(0L) }

    val flatNodes = remember(rootNodes) { flattenAll(rootNodes) }

    Box(modifier = modifier.fillMaxSize()) {
        // ── No-screenshot fallback: draw coloured node rectangles ──────────
        if (screenshot == null && rootNodes.isNotEmpty()) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val canvasW = constraints.maxWidth.toFloat()
                val canvasH = constraints.maxHeight.toFloat()

                // Determine the scene bounds from all root nodes
                val allFlat = flatNodes
                val maxR = allFlat.mapNotNull { it.bounds?.let { b -> b.x + b.width } }.maxOrNull() ?: 1080
                val maxB = allFlat.mapNotNull { it.bounds?.let { b -> b.y + b.height } }.maxOrNull() ?: 1920
                val scX = canvasW / maxR.toFloat().coerceAtLeast(1f)
                val scY = canvasH / maxB.toFloat().coerceAtLeast(1f)
                val sc = minOf(scX, scY)
                val offX = (canvasW - maxR * sc) / 2f
                val offY = (canvasH - maxB * sc) / 2f

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Move) { event ->
                            val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                            mousePos = pos
                            val ix = (pos.x - offX) / sc
                            val iy = (pos.y - offY) / sc
                            hoveredNode = hitTest(flatNodes, ix, iy)
                        }
                        .onPointerEvent(PointerEventType.Press) { event ->
                            val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                            val ix = (pos.x - offX) / sc
                            val iy = (pos.y - offY) / sc
                            hitTest(flatNodes, ix, iy)?.let { onNodeSelected(it) }
                        }
                ) {
                    // Background
                    drawRect(color = Color(0xFF0D1117))

                    // Draw each node as a colored rectangle
                    allFlat.forEach { node ->
                        val b = node.bounds ?: return@forEach
                        val (fill, border) = nodeColors(node)
                        val l = offX + b.x * sc
                        val t = offY + b.y * sc
                        val w = b.width * sc
                        val h = b.height * sc
                        if (w > 0 && h > 0) {
                            drawRect(color = fill, topLeft = Offset(l, t), size = Size(w, h))
                            drawRect(
                                color = border.copy(alpha = 0.6f),
                                topLeft = Offset(l, t),
                                size = Size(w, h),
                                style = Stroke(width = 1f)
                            )
                        }
                    }

                    // Bookmarked nodes: AccentGreen fill (25% alpha) + solid border
                    allFlat.filter { it.id in bookmarkedNodeIds }.forEach { node ->
                        val b = node.bounds ?: return@forEach
                        val l = offX + b.x * sc
                        val t = offY + b.y * sc
                        val w = b.width * sc
                        val h = b.height * sc
                        if (w > 0 && h > 0) {
                            drawRect(
                                color = AccentGreen.copy(alpha = 0.25f),
                                topLeft = Offset(l, t),
                                size = Size(w, h)
                            )
                            drawRect(
                                color = AccentGreen,
                                topLeft = Offset(l, t),
                                size = Size(w, h),
                                style = Stroke(width = 2f)
                            )
                        }
                    }

                    // Hovered node
                    hoveredNode?.takeIf { it.id != selectedNode?.id }?.bounds?.let { b ->
                        val l = offX + b.x * sc
                        val t = offY + b.y * sc
                        val w = b.width * sc
                        val h = b.height * sc
                        drawRect(
                            color = Color.White.copy(alpha = 0.5f),
                            topLeft = Offset(l, t),
                            size = Size(w, h),
                            style = Stroke(width = 1.5f)
                        )
                    }

                    // Selected node: white dashed border
                    selectedNode?.bounds?.let { b ->
                        val l = offX + b.x * sc
                        val t = offY + b.y * sc
                        val w = b.width * sc
                        val h = b.height * sc
                        drawRect(
                            color = highlightColor.copy(alpha = 0.15f),
                            topLeft = Offset(l, t),
                            size = Size(w, h)
                        )
                        drawRect(
                            color = NodeSelectedBorder,
                            topLeft = Offset(l, t),
                            size = Size(w, h),
                            style = Stroke(
                                width = 2.5f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                            )
                        )
                    }
                }

                // Hover tooltip for no-screenshot mode
                hoveredNode?.takeIf { it.id != selectedNode?.id }?.let { hovered ->
                    val tooltip = buildString {
                        append(friendlyNodeKind(hovered))
                        hovered.resourceId?.let { append("\n@${it.substringAfterLast('/')}") }
                        hovered.text?.let { if (it.isNotBlank()) append("\n\"${it.take(30)}\"") }
                        hovered.bounds?.let { b -> append("\n[${b.x},${b.y}] ${b.width}×${b.height}") }
                    }
                    val tx = (mousePos.x + 12f).dp.coerceAtMost((canvasW - 200f).dp)
                    val ty = (mousePos.y + 12f).dp.coerceAtMost((canvasH - 80f).dp)
                    Box(
                        modifier = Modifier
                            .offset(tx, ty)
                            .background(
                                color = colorScheme.inverseSurface.copy(alpha = 0.92f),
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            tooltip,
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.inverseOnSurface,
                            fontSize = 10.sp
                        )
                    }
                }

                // "No screenshot" label
                Box(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "Structural view — no screenshot captured",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }
            }
        } else if (screenshot == null) {
            // Empty state — no tree, no screenshot
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                            "Or press R to refresh"
                        else
                            "Plug in your phone or enter a wireless ADB address",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    )
                }
            }
        } else {
            // ── Screenshot mode ──────────────────────────────────────────────
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val canvasW = constraints.maxWidth.toFloat()
                val canvasH = constraints.maxHeight.toFloat()
                val imgW = screenshot.width.toFloat()
                val imgH = screenshot.height.toFloat()
                val fitScale = minOf(canvasW / imgW.coerceAtLeast(1f), canvasH / imgH.coerceAtLeast(1f))
                val fitOffX = (canvasW - imgW * fitScale) / 2f
                val fitOffY = (canvasH - imgH * fitScale) / 2f
                val effScale = fitScale * zoomScale
                val effOffX = fitOffX + panOffset.x
                val effOffY = fitOffY + panOffset.y

                fun canvasToImage(pos: Offset): Offset {
                    val ix = (pos.x - effOffX) / effScale
                    val iy = (pos.y - effOffY) / effScale
                    return Offset(ix, iy)
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            val factor = if (delta < 0) 1.12f else 0.88f
                            zoomScale = (zoomScale * factor).coerceIn(0.15f, 10f)
                        }
                        .onPointerEvent(PointerEventType.Move) { event ->
                            val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                            mousePos = pos
                            val imgPos = canvasToImage(pos)
                            hoveredNode = hitTest(flatNodes, imgPos.x, imgPos.y)
                            showContextMenu = false
                        }
                        .onPointerEvent(PointerEventType.Press) { event ->
                            val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                            if (event.buttons.isSecondaryPressed) {
                                mousePos = pos
                                showContextMenu = true
                            } else if (event.buttons.isPrimaryPressed) {
                                val now = System.currentTimeMillis()
                                if (now - lastClickTime < 300L) {
                                    zoomScale = 1f
                                    panOffset = Offset.Zero
                                } else {
                                    val imgPos = canvasToImage(pos)
                                    hitTest(flatNodes, imgPos.x, imgPos.y)?.let { onNodeSelected(it) }
                                }
                                lastClickTime = now
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { _, drag ->
                                panOffset += drag
                            }
                        }
                ) {
                    drawImage(
                        image = screenshot,
                        dstOffset = IntOffset(effOffX.roundToInt(), effOffY.roundToInt()),
                        dstSize = IntSize(
                            (imgW * effScale).roundToInt().coerceAtLeast(1),
                            (imgH * effScale).roundToInt().coerceAtLeast(1)
                        )
                    )

                    // Bookmarked nodes: AccentGreen highlight
                    flatNodes.filter { it.id in bookmarkedNodeIds }.forEach { node ->
                        node.bounds?.let { b ->
                            val l = effOffX + b.x * effScale
                            val t = effOffY + b.y * effScale
                            val w = b.width * effScale
                            val h = b.height * effScale
                            drawRect(
                                color = AccentGreen.copy(alpha = 0.25f),
                                topLeft = Offset(l, t),
                                size = Size(w, h)
                            )
                            drawRect(
                                color = AccentGreen,
                                topLeft = Offset(l, t),
                                size = Size(w, h),
                                style = Stroke(width = 2f)
                            )
                        }
                    }

                    // Hovered (not selected) node
                    hoveredNode?.takeIf { it.id != selectedNode?.id }?.bounds?.let { b ->
                        val l = effOffX + b.x * effScale
                        val t = effOffY + b.y * effScale
                        val w = b.width * effScale
                        val h = b.height * effScale
                        drawRect(
                            color = Color.White.copy(alpha = 0.5f),
                            topLeft = Offset(l, t),
                            size = Size(w, h),
                            style = Stroke(width = 1.5f)
                        )
                    }

                    // Selected node
                    selectedNode?.bounds?.let { b ->
                        val l = effOffX + b.x * effScale
                        val t = effOffY + b.y * effScale
                        val w = b.width * effScale
                        val h = b.height * effScale
                        drawRect(
                            color = highlightColor.copy(alpha = 0.12f),
                            topLeft = Offset(l, t),
                            size = Size(w, h)
                        )
                        drawRect(
                            color = highlightColor,
                            topLeft = Offset(l, t),
                            size = Size(w, h),
                            style = Stroke(width = 2.5f)
                        )
                    }
                }

                hoveredNode?.takeIf { it.id != selectedNode?.id }?.let { hovered ->
                    val tooltip = buildString {
                        append(friendlyNodeKind(hovered))
                        hovered.resourceId?.let { append("\n@${it.substringAfterLast('/')}") }
                        hovered.text?.let { if (it.isNotBlank()) append("\n\"${it.take(30)}\"") }
                        hovered.bounds?.let { b -> append("\n[${b.x},${b.y}] ${b.width}×${b.height}") }
                    }
                    val tx = (mousePos.x + 12f).dp.coerceAtMost((canvasW - 200f).dp)
                    val ty = (mousePos.y + 12f).dp.coerceAtMost((canvasH - 80f).dp)
                    Box(
                        modifier = Modifier
                            .offset(tx, ty)
                            .background(
                                color = colorScheme.inverseSurface.copy(alpha = 0.92f),
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            tooltip,
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.inverseOnSurface,
                            fontSize = 10.sp
                        )
                    }
                }

                ZoomControls(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    zoomScale = zoomScale,
                    onZoomIn = { zoomScale = (zoomScale * 1.25f).coerceAtMost(10f) },
                    onZoomOut = { zoomScale = (zoomScale * 0.8f).coerceAtLeast(0.15f) },
                    onFit = { zoomScale = 1f; panOffset = Offset.Zero }
                )

                if (showContextMenu) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = { showContextMenu = false },
                        offset = DpOffset(mousePos.x.dp, mousePos.y.dp)
                    ) {
                        selectedNode?.bounds?.let { b ->
                            DropdownMenuItem(
                                text = { Text("Copy bounds", style = MaterialTheme.typography.bodySmall) },
                                onClick = {
                                    copyText("[${b.x},${b.y}][${b.x + b.width},${b.y + b.height}]")
                                    showContextMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy selector", style = MaterialTheme.typography.bodySmall) },
                                onClick = {
                                    val sel = selectedNode.resourceId?.let { "By.res(\"$it\")" }
                                        ?: selectedNode.text?.let { "By.text(\"$it\")" }
                                        ?: "By.clazz(\"${selectedNode.className}\")"
                                    copyText(sel)
                                    showContextMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Save screenshot…", style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                showContextMenu = false
                                saveScreenshot(screenshot)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy screenshot path", style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                copyText("Use File → Export to save the screenshot")
                                showContextMenu = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomControls(
    modifier: Modifier,
    zoomScale: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .background(colorScheme.surface.copy(alpha = 0.88f), shape = MaterialTheme.shapes.small)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTextButton("-", onClick = onZoomOut)
        Text(
            "${(zoomScale * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        IconTextButton("+", onClick = onZoomIn)
        Spacer(Modifier.width(4.dp))
        IconTextButton("⊡", onClick = onFit)
    }
}

@Composable
private fun IconTextButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
    )
}

private fun saveScreenshot(screenshot: ImageBitmap?) {
    screenshot ?: return
    try {
        val chooser = JFileChooser()
        chooser.selectedFile = File("screenshot.png")
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            val awtImage = screenshot.toAwtImage()
            val target = if (file.extension.lowercase() == "png") file else File("${file.absolutePath}.png")
            ImageIO.write(awtImage, "PNG", target)
        }
    } catch (_: Exception) {}
}
