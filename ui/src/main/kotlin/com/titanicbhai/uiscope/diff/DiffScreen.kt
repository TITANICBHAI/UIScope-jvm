package com.titanicbhai.uiscope.diff

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.Session
import com.titanicbhai.uiscope.repository.SessionRepository
import com.titanicbhai.uiscope.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ── Data model ────────────────────────────────────────────────────────────────

private data class NodeSnapshot(
    val label: String,
    val text: String?,
    val isEnabled: Boolean,
    val bounds: Bounds?
)

private enum class DiffStatus { ADDED, REMOVED, CHANGED }

private data class DiffItem(
    val nodeId: String,
    val label: String,
    val status: DiffStatus,
    val detail: String = "",
    val boundsA: Bounds? = null,
    val boundsB: Bounds? = null
)

// ── Parsing ───────────────────────────────────────────────────────────────────

private fun extractNodes(treeJson: String): Map<String, NodeSnapshot> {
    val result = mutableMapOf<String, NodeSnapshot>()
    if (treeJson.isBlank()) return result

    val resIdRx   = Regex(""""resourceId"\s*:\s*"([^"]*)"""")
    val textRx    = Regex(""""text"\s*:\s*"([^"]*)"""")
    val nameRx    = Regex(""""name"\s*:\s*"([^"]*)"""")
    val classRx   = Regex(""""className"\s*:\s*"([^"]*)"""")
    val enabledRx = Regex(""""isEnabled"\s*:\s*(true|false)""")
    val boundsRx  = Regex(""""bounds"\s*:\s*\{"x"\s*:\s*(-?\d+),"y"\s*:\s*(-?\d+),"width"\s*:\s*(\d+),"height"\s*:\s*(\d+)\}""")

    var depth = 0
    var start = 0
    for (i in treeJson.indices) {
        when (treeJson[i]) {
            '{' -> { if (depth == 0) start = i; depth++ }
            '}' -> {
                depth--
                if (depth == 0) {
                    val obj = treeJson.substring(start, i + 1)
                    val resId = resIdRx.find(obj)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                    if (resId != null && resId !in result) {
                        val text    = textRx.find(obj)?.groupValues?.get(1)
                        val name    = nameRx.find(obj)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                        val cls     = classRx.find(obj)?.groupValues?.get(1) ?: ""
                        val enabled = enabledRx.find(obj)?.groupValues?.get(1) != "false"
                        val label   = name ?: cls.substringAfterLast('.').ifBlank { resId.substringAfterLast('/') }
                        val bm      = boundsRx.find(obj)?.groupValues
                        val bounds  = if (bm != null && bm.size >= 5)
                            Bounds(bm[1].toIntOrNull() ?: 0, bm[2].toIntOrNull() ?: 0,
                                   bm[3].toIntOrNull() ?: 0, bm[4].toIntOrNull() ?: 0)
                        else null
                        result[resId] = NodeSnapshot(label, text, enabled, bounds)
                    }
                }
            }
        }
    }
    return result
}

private fun computeDiff(
    nodesA: Map<String, NodeSnapshot>,
    nodesB: Map<String, NodeSnapshot>
): List<DiffItem> {
    val all = mutableListOf<DiffItem>()
    nodesB.forEach { (id, snap) ->
        if (id !in nodesA) all.add(DiffItem(id, snap.label, DiffStatus.ADDED, boundsB = snap.bounds))
    }
    nodesA.forEach { (id, snap) ->
        if (id !in nodesB) all.add(DiffItem(id, snap.label, DiffStatus.REMOVED, boundsA = snap.bounds))
    }
    nodesA.forEach { (id, snapA) ->
        val snapB = nodesB[id] ?: return@forEach
        val changes = buildList {
            if (snapA.text != snapB.text) add("""text: "${snapA.text}" → "${snapB.text}"""")
            if (snapA.isEnabled != snapB.isEnabled) add("enabled: ${snapA.isEnabled} → ${snapB.isEnabled}")
        }
        if (changes.isNotEmpty()) {
            all.add(DiffItem(id, snapA.label, DiffStatus.CHANGED,
                changes.joinToString(" · "), boundsA = snapA.bounds, boundsB = snapB.bounds))
        }
    }
    return all.sortedBy { when (it.status) { DiffStatus.ADDED -> 0; DiffStatus.REMOVED -> 1; DiffStatus.CHANGED -> 2 } }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun DiffScreen(onBack: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val sessionRepo = remember { SessionRepository() }

    var sessions    by remember { mutableStateOf<List<Session>>(emptyList()) }
    var isLoading   by remember { mutableStateOf(true) }
    var sessionA    by remember { mutableStateOf<Session?>(null) }
    var sessionB    by remember { mutableStateOf<Session?>(null) }
    var diffItems   by remember { mutableStateOf<List<DiffItem>?>(null) }
    var isComputing by remember { mutableStateOf(false) }
    var expandedA   by remember { mutableStateOf(false) }
    var expandedB   by remember { mutableStateOf(false) }
    var showVisual  by remember { mutableStateOf(false) }
    var screenshotA by remember { mutableStateOf<ImageBitmap?>(null) }
    var screenshotB by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(Unit) {
        sessions  = withContext(Dispatchers.IO) { sessionRepo.getAll() }
        isLoading = false
    }

    // Load screenshots when sessions are selected
    LaunchedEffect(sessionA) {
        screenshotA = withContext(Dispatchers.IO) {
            sessionA?.screenshotPath?.let { path ->
                runCatching {
                    val f = File(path)
                    if (f.exists()) loadImageBitmap(f.inputStream()) else null
                }.getOrNull()
            }
        }
    }
    LaunchedEffect(sessionB) {
        screenshotB = withContext(Dispatchers.IO) {
            sessionB?.screenshotPath?.let { path ->
                runCatching {
                    val f = File(path)
                    if (f.exists()) loadImageBitmap(f.inputStream()) else null
                }.getOrNull()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colorScheme.background)) {
        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(colorScheme.surface)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onBack) {
                    Text("← Back", color = colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
                Text("│", color = colorScheme.outline)
                Text(
                    "⚖  Diff Mode",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (diffItems != null) {
                    val hasScreenshots = screenshotA != null || screenshotB != null
                    if (hasScreenshots) {
                        FilterChip(
                            selected = showVisual,
                            onClick = { showVisual = !showVisual },
                            label = { Text(if (showVisual) "📸 Visual" else "📋 Text", fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
                Text(
                    "Compare element trees between two captured sessions",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.4f))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        if (sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("⚖", style = MaterialTheme.typography.displaySmall)
                    Text("No sessions to compare", style = MaterialTheme.typography.bodyLarge, color = colorScheme.onSurfaceVariant)
                    Text(
                        "Capture at least two inspections in the Inspector to use Diff Mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                }
            }
            return@Column
        }

        // ── Session pickers ───────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SessionPicker(
                label = "Session A  (before)",
                sessions = sessions, selected = sessionA,
                expanded = expandedA, onExpand = { expandedA = true },
                onDismiss = { expandedA = false },
                onSelect = { sessionA = it; expandedA = false; diffItems = null; showVisual = false },
                modifier = Modifier.weight(1f)
            )
            SessionPicker(
                label = "Session B  (after)",
                sessions = sessions, selected = sessionB,
                expanded = expandedB, onExpand = { expandedB = true },
                onDismiss = { expandedB = false },
                onSelect = { sessionB = it; expandedB = false; diffItems = null; showVisual = false },
                modifier = Modifier.weight(1f)
            )
        }

        // ── Compute button ────────────────────────────────────────────────────
        if (sessionA != null && sessionB != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = {
                        val sa = sessionA ?: return@Button
                        val sb = sessionB ?: return@Button
                        isComputing = true
                        val nodesA = extractNodes(sa.treeJson ?: "")
                        val nodesB = extractNodes(sb.treeJson ?: "")
                        diffItems  = computeDiff(nodesA, nodesB)
                        isComputing = false
                        showVisual = screenshotA != null || screenshotB != null
                    },
                    enabled = !isComputing
                ) {
                    if (isComputing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Compute Diff")
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))

        // ── Results ───────────────────────────────────────────────────────────
        val items = diffItems
        if (items == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("⚖", style = MaterialTheme.typography.displaySmall)
                    Text(
                        if (sessionA == null || sessionB == null)
                            "Select two sessions above to compare them"
                        else
                            "Press Compute Diff to see the differences",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                }
            }
        } else {
            val added   = items.count { it.status == DiffStatus.ADDED }
            val removed = items.count { it.status == DiffStatus.REMOVED }
            val changed = items.count { it.status == DiffStatus.CHANGED }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Results:", style = MaterialTheme.typography.labelMedium, color = colorScheme.onSurfaceVariant)
                SummaryChip("+$added added",     AccentGreen)
                SummaryChip("-$removed removed", AccentRed)
                if (changed > 0) SummaryChip("~$changed changed", AccentOrange)
            }

            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("✓", style = MaterialTheme.typography.displaySmall, color = AccentGreen)
                        Text("No differences found", style = MaterialTheme.typography.bodyLarge, color = colorScheme.onSurface)
                        Text("Both sessions have identical element structures.", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
                    }
                }
            } else if (showVisual && (screenshotA != null || screenshotB != null)) {
                VisualDiffView(
                    screenshotA = screenshotA,
                    screenshotB = screenshotB,
                    diffItems = items
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(items) { item -> DiffItemRow(item) }
                }
            }
        }
    }
}

// ── Visual screenshot diff overlay ────────────────────────────────────────────

@Composable
private fun VisualDiffView(
    screenshotA: ImageBitmap?,
    screenshotB: ImageBitmap?,
    diffItems: List<DiffItem>
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Session A — show REMOVED and CHANGED
        ScreenshotOverlay(
            label = "A — Before",
            screenshot = screenshotA,
            overlays = diffItems.mapNotNull { item ->
                when (item.status) {
                    DiffStatus.REMOVED -> item.boundsA?.let { OverlayRect(it, AccentRed) }
                    DiffStatus.CHANGED -> item.boundsA?.let { OverlayRect(it, AccentOrange) }
                    else -> null
                }
            },
            modifier = Modifier.weight(1f)
        )
        // Session B — show ADDED and CHANGED
        ScreenshotOverlay(
            label = "B — After",
            screenshot = screenshotB,
            overlays = diffItems.mapNotNull { item ->
                when (item.status) {
                    DiffStatus.ADDED   -> item.boundsB?.let { OverlayRect(it, AccentGreen) }
                    DiffStatus.CHANGED -> item.boundsB?.let { OverlayRect(it, AccentOrange) }
                    else -> null
                }
            },
            modifier = Modifier.weight(1f)
        )
    }
}

private data class OverlayRect(val bounds: Bounds, val color: Color)

@Composable
private fun ScreenshotOverlay(
    label: String,
    screenshot: ImageBitmap?,
    overlays: List<OverlayRect>,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .border(1.dp, colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
        ) {
            if (screenshot == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No screenshot available",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val imgW = screenshot.width.toFloat()
                    val imgH = screenshot.height.toFloat()
                    val canvasW = size.width
                    val canvasH = size.height
                    val scaleX = canvasW / imgW
                    val scaleY = canvasH / imgH
                    val scale = minOf(scaleX, scaleY)
                    val offsetX = (canvasW - imgW * scale) / 2f
                    val offsetY = (canvasH - imgH * scale) / 2f

                    drawImage(
                        image = screenshot,
                        dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(
                            (imgW * scale).toInt(),
                            (imgH * scale).toInt()
                        )
                    )

                    overlays.forEach { overlay ->
                        val b = overlay.bounds
                        val left   = offsetX + b.x * scale
                        val top    = offsetY + b.y * scale
                        val width  = b.width * scale
                        val height = b.height * scale
                        if (width > 0 && height > 0) {
                            drawRect(
                                color = overlay.color.copy(alpha = 0.18f),
                                topLeft = Offset(left, top),
                                size = Size(width, height)
                            )
                            drawRect(
                                color = overlay.color,
                                topLeft = Offset(left, top),
                                size = Size(width, height),
                                style = Stroke(width = 2f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Helper composables ────────────────────────────────────────────────────────

@Composable
private fun SessionPicker(
    label: String,
    sessions: List<Session>,
    selected: Session?,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (Session) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Box {
            OutlinedButton(onClick = onExpand, modifier = Modifier.fillMaxWidth()) {
                Text(
                    selected?.let { "${it.appName ?: it.packageName ?: "Session"} — ${it.mode.name}" } ?: "Select session…",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
                sessions.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(s.appName ?: s.packageName ?: "Session", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${s.mode.name} · ${s.deviceName ?: ""}${if (s.screenshotPath != null) " 📸" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = { onSelect(s) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DiffItemRow(item: DiffItem) {
    val colorScheme = MaterialTheme.colorScheme
    val (bgColor, borderColor, prefix) = when (item.status) {
        DiffStatus.ADDED   -> Triple(AccentGreen.copy(alpha = 0.08f),  AccentGreen,  "+")
        DiffStatus.REMOVED -> Triple(AccentRed.copy(alpha = 0.08f),    AccentRed,    "−")
        DiffStatus.CHANGED -> Triple(AccentOrange.copy(alpha = 0.08f), AccentOrange, "~")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(bgColor, RoundedCornerShape(4.dp))
            .border(0.5.dp, borderColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            prefix,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = borderColor, fontSize = 13.sp,
            modifier = Modifier.width(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.label,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = colorScheme.onSurface, maxLines = 1
            )
            Text(
                item.nodeId,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.55f), maxLines = 1
            )
            if (item.detail.isNotBlank()) {
                Text(
                    item.detail,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
                    color = borderColor.copy(alpha = 0.85f), maxLines = 2
                )
            }
        }
        Box(
            modifier = Modifier
                .background(borderColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(item.status.name, style = MaterialTheme.typography.labelSmall, color = borderColor, fontSize = 9.sp)
        }
    }
}
