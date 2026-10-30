package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.CaptureViewport
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.model.HitTest
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val canvasLogger = Logger.getLogger("uiscope.ui.canvas")

/**
 * The canvas (plan P4-5, 4.5): the screenshot fitted by default, Ctrl+wheel zoom anchored at the cursor, plain wheel and
 * middle-drag pan (a plain click never pans), Fit and 100 percent, a show-all-bounds toggle, hover outline with a tooltip,
 * a filled accent for the selection, and a wireframe fallback when there is no (readable) screenshot. Clicks hit-test
 * through [HitTest] and report the element. Every coordinate conversion goes through the pure [CaptureViewport] - the
 * drawing code never maps coordinates itself.
 */
@Composable
fun CanvasPane(
    capture: Capture?,
    index: ElementIndex?,
    selected: ElementPath?,
    onSelect: (ElementPath?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var showAllBounds by remember { mutableStateOf(false) }
    var viewport by remember(capture) { mutableStateOf<CaptureViewport?>(null) }
    LaunchedEffect(capture, canvasSize) {
        viewport = capture?.let { CaptureViewport.fit(it.screen, canvasSize.width.toDouble(), canvasSize.height.toDouble()) }
    }
    // Decoding happens off the UI thread; a broken PNG is logged and the wireframe takes over (plan 4.5).
    val image = produceState<ImageBitmap?>(initialValue = null, capture?.screenshot?.file) {
        val file = capture?.screenshot?.file
        value = if (file == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    val decoded = javax.imageio.ImageIO.read(Path.of(file).toFile()) ?: throw java.io.IOException("no image reader for the file")
                    decoded.toComposeImageBitmap()
                } catch (t: Throwable) {
                    canvasLogger.log(Level.WARNING, "the screenshot could not be decoded (${t.javaClass.simpleName}); showing the wireframe", t)
                    null
                }
            }
        }
    }
    var hovered by remember { mutableStateOf<ElementNode?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val outlineColor = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val hoverColor = MaterialTheme.colorScheme.tertiary
    val wireColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = UiScopeTokens.spaceS, vertical = UiScopeTokens.spaceXs),
            horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(onClick = {
                viewport = capture?.let { CaptureViewport.fit(it.screen, canvasSize.width.toDouble(), canvasSize.height.toDouble()) }
            }, label = { Text("Fit") })
            AssistChip(onClick = {
                viewport = capture?.screen?.let { screen -> viewport?.actualSize(screen, canvasSize.width.toDouble(), canvasSize.height.toDouble()) }
            }, label = { Text("100%") })
            Text(viewport?.let { "${(it.effectiveScale * 100).roundToInt()}%" } ?: "", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.toggleable(value = showAllBounds, onValueChange = { showAllBounds = it })) {
                Switch(checked = showAllBounds, onCheckedChange = null)
                Text("All bounds", modifier = Modifier.padding(start = UiScopeTokens.spaceXs))
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .testTag("uiscope.canvas")
                .onSizeChanged { canvasSize = it }
                .pointerInput(capture, index) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val current = viewport ?: continue
                            when (event.type) {
                                PointerEventType.Scroll -> {
                                    val change = event.changes.first()
                                    if (event.keyboardModifiers.isCtrlPressed) {
                                        val factor = if (change.scrollDelta.y < 0) 1.2 else 1 / 1.2
                                        viewport = current.zoomBy(change.position.x.toDouble(), change.position.y.toDouble(), factor)
                                    } else {
                                        viewport = current.panBy(-change.scrollDelta.x * 20.0, -change.scrollDelta.y * 20.0)
                                    }
                                    change.consume()
                                }
                                PointerEventType.Press -> {
                                    val start = event.changes.first().position
                                    val panning = event.buttons.isTertiaryPressed
                                    var last = start
                                    var dragged = false
                                    while (true) {
                                        val move = awaitPointerEvent()
                                        val change = move.changes.first()
                                        if (move.type == PointerEventType.Move && panning && move.buttons.isTertiaryPressed) {
                                            viewport = (viewport ?: current).panBy(
                                                change.position.x - last.x.toDouble(),
                                                change.position.y - last.y.toDouble(),
                                            )
                                            last = change.position
                                            dragged = true
                                            change.consume()
                                        }
                                        if (move.type == PointerEventType.Release) {
                                            if (!panning && !dragged) {
                                                val point = (viewport ?: current).toCapture(change.position.x.toDouble(), change.position.y.toDouble())
                                                val hit = index?.let { HitTest.hit(it, point.x, point.y) }
                                                onSelect(hit?.path)
                                            }
                                            break
                                        }
                                        if (move.type == PointerEventType.Exit) break
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                .pointerInput(capture, index) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Move) {
                                val point = viewport?.toCapture(event.changes.first().position.x.toDouble(), event.changes.first().position.y.toDouble())
                                hovered = if (point != null && index != null) HitTest.hit(index, point.x, point.y) else null
                            }
                        }
                    }
                },
        ) {
            val vp = viewport ?: return@Box
            Canvas(Modifier.fillMaxSize()) {
                if (image.value != null) {
                    val dst = vp.toCanvas(Bounds(capture!!.screen.originX.toInt(), capture.screen.originY.toInt(), capture.screen.width, capture.screen.height))
                    drawImage(
                        image.value!!,
                        dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
                        dstSize = IntSize(dst.width.roundToInt().coerceAtLeast(1), dst.height.roundToInt().coerceAtLeast(1)),
                    )
                } else if (capture != null) {
                    // Wireframe fallback: every element as an outline in capture space.
                    index?.nodes?.forEach { node ->
                        val r = vp.toCanvas(node.bounds)
                        drawRect(wireColor.copy(alpha = 0.6f), topLeft = Offset(r.left.toFloat(), r.top.toFloat()), size = Size(r.width.toFloat().coerceAtLeast(1f), r.height.toFloat().coerceAtLeast(1f)), style = Stroke(1f))
                    }
                }
                if (showAllBounds && index != null) {
                    index.nodes.forEach { node ->
                        val r = vp.toCanvas(node.bounds)
                        drawRect(accent.copy(alpha = 0.5f), topLeft = Offset(r.left.toFloat(), r.top.toFloat()), size = Size(r.width.toFloat(), r.height.toFloat()), style = Stroke(1f))
                    }
                }
                selected?.let { path -> index?.byPath?.get(path)?.let { node -> drawElementFill(vp, node.bounds, accent, onAccent, textMeasurer, label(node)) } }
                hovered?.takeIf { it.path != selected }?.let { node -> drawElementOutline(vp, node.bounds, hoverColor, textMeasurer, label(node)) }
            }
        }
    }
}

private fun label(node: ElementNode): String =
    node.text ?: node.name ?: node.identifier ?: node.className.substringAfterLast('.')

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawElementFill(
    vp: CaptureViewport,
    bounds: Bounds,
    fill: Color,
    onFill: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
) {
    val r = vp.toCanvas(bounds)
    drawRect(fill.copy(alpha = 0.35f), topLeft = Offset(r.left.toFloat(), r.top.toFloat()), size = Size(r.width.toFloat(), r.height.toFloat()))
    drawRect(fill, topLeft = Offset(r.left.toFloat(), r.top.toFloat()), size = Size(r.width.toFloat(), r.height.toFloat()), style = Stroke(2f))
    val measured = textMeasurer.measure(AnnotatedString(text), TextStyle(color = onFill, fontSize = 11.sp))
    drawRect(onFill.copy(alpha = 0.9f), topLeft = Offset(r.left.toFloat(), r.top.toFloat()), size = Size(measured.size.width.toFloat() + 8f, measured.size.height.toFloat() + 4f))
    drawText(measured, topLeft = Offset(r.left.toFloat() + 4f, r.top.toFloat() + 2f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawElementOutline(
    vp: CaptureViewport,
    bounds: Bounds,
    color: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
) {
    val r = vp.toCanvas(bounds)
    drawRect(color, topLeft = Offset(r.left.toFloat(), r.top.toFloat()), size = Size(r.width.toFloat(), r.height.toFloat()), style = Stroke(2f))
    val measured = textMeasurer.measure(AnnotatedString(text), TextStyle(color = color, fontSize = 11.sp))
    drawRect(color.copy(alpha = 0.9f), topLeft = Offset(r.left.toFloat(), r.top.toFloat() - measured.size.height - 4f), size = Size(measured.size.width.toFloat() + 8f, measured.size.height.toFloat() + 4f))
    drawText(measured, topLeft = Offset(r.left.toFloat() + 4f, r.top.toFloat() - measured.size.height - 2f))
}
