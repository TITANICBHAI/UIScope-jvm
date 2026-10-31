package com.titanicbhai.uiscope.ui.compare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.diff.DiffMatch
import com.titanicbhai.uiscope.diff.DiffResult
import com.titanicbhai.uiscope.diff.MatchPass
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.json.JsonWriter
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.CaptureViewport
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.snapshot.SnapshotMeta
import com.titanicbhai.uiscope.snapshot.SnapshotSort
import com.titanicbhai.uiscope.snapshot.SnapshotStore
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

/** The shell side of the Compare screen: toasts and the native save dialog for the diff export. */
data class CompareCallbacks(
    val onMessage: (String) -> Unit = {},
    val saveDiff: suspend (suggestedName: String, content: ByteArray) -> Outcome<Unit> =
        { _, _ -> Outcome.Failure(CodedError(ErrorCodes.UNEXPECTED, "saving is unavailable")) },
)

/** One side of a comparison: the live capture of the active Inspect target, or a saved snapshot. */
sealed interface CompareSource {
    data object Live : CompareSource

    data class Snapshot(val meta: SnapshotMeta) : CompareSource
}

private enum class DiffFilter(val label: String) {
    ALL("All"),
    CHANGED("Changed"),
    MOVED("Moved"),
    ADDED("Added"),
    REMOVED("Removed"),
}

private val listTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private val compareLogger = Logger.getLogger("uiscope.compare")

/**
 * The Compare screen (plan P6-2): pick two captures (the live capture of the active target and/or snapshots),
 * run the P6-1 matching engine over them, browse the result through the Added/Removed/Changed/Moved chips,
 * click an entry to locate the element on both side-by-side screenshots (coloured boxes: red removed, green
 * added, the selected element accent), and export the diff as JSON or text.
 */
@Composable
fun CompareScreen(
    store: SnapshotStore?,
    liveCapture: Capture?,
    callbacks: CompareCallbacks,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var sideA by remember { mutableStateOf<CompareSource?>(null) }
    var sideB by remember { mutableStateOf<CompareSource?>(null) }
    var beforeCapture by remember { mutableStateOf<Capture?>(null) }
    var afterCapture by remember { mutableStateOf<Capture?>(null) }
    var result by remember { mutableStateOf<DiffResult?>(null) }
    var filter by remember { mutableStateOf(DiffFilter.ALL) }
    var selectedBefore by remember { mutableStateOf<ElementNode?>(null) }
    var selectedAfter by remember { mutableStateOf<ElementNode?>(null) }
    var running by remember { mutableStateOf(false) }

    val snapshots = produceState<List<SnapshotMeta>>(emptyList(), store) {
        value = when (val listed = store?.list(null, SnapshotSort.NEWEST)) {
            is Outcome.Success -> listed.value
            else -> emptyList()
        }
    }

    fun load(source: CompareSource?): Capture? = when (source) {
        is CompareSource.Live -> liveCapture
        is CompareSource.Snapshot -> null // resolved in run(): a suspend load
        null -> null
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = UiScopeTokens.spaceM, vertical = UiScopeTokens.spaceS),
            horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            SourcePicker("Before", sideA, liveCapture, snapshots.value) { sideA = it }
            Text("vs", style = MaterialTheme.typography.labelLarge)
            SourcePicker("After", sideB, liveCapture, snapshots.value) { sideB = it }
            Button(
                enabled = !running && sideA != null && sideB != null,
                onClick = {
                    running = true
                    scope.launch {
                        val a = if (sideA is CompareSource.Snapshot) {
                            (store?.load((sideA as CompareSource.Snapshot).meta.id) as? Outcome.Success)?.value?.capture
                        } else {
                            load(sideA)
                        }
                        val b = if (sideB is CompareSource.Snapshot) {
                            (store?.load((sideB as CompareSource.Snapshot).meta.id) as? Outcome.Success)?.value?.capture
                        } else {
                            load(sideB)
                        }
                        if (a == null || b == null) {
                            callbacks.onMessage("Both sides need a capture (the live side needs one on screen).")
                        } else {
                            beforeCapture = a
                            afterCapture = b
                            result = withContext(Dispatchers.Default) { DiffMatch.diff(a, b) }
                            selectedBefore = null
                            selectedAfter = null
                        }
                        running = false
                    }
                },
            ) { Text(if (running) "Comparing..." else "Compare") }
        }
        val diff = result
        if (diff == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(
                    "Pick a Before and an After capture and press Compare.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(UiScopeTokens.spaceL),
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = UiScopeTokens.spaceM),
                horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS),
            ) {
                for (f in DiffFilter.entries) {
                    AssistChip(onClick = { filter = f }, label = { Text(f.label) })
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        when (val saved = callbacks.saveDiff("uiscope-diff.json", diffAsJson(diff))) {
                            is Outcome.Success -> callbacks.onMessage("The diff was saved as JSON.")
                            is Outcome.Failure -> callbacks.onMessage("The diff export failed (${saved.error.code}).")
                        }
                    }
                }) { Text("Export JSON") }
                OutlinedButton(onClick = {
                    scope.launch {
                        when (val saved = callbacks.saveDiff("uiscope-diff.txt", diffAsText(diff))) {
                            is Outcome.Success -> callbacks.onMessage("The diff was saved as text.")
                            is Outcome.Failure -> callbacks.onMessage("The diff export failed (${saved.error.code}).")
                        }
                    }
                }) { Text("Export text") }
            }
            Text(
                "${diff.pairs.size} matched - ${diff.added.size} added - ${diff.removed.size} removed",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = UiScopeTokens.spaceM, vertical = UiScopeTokens.spaceXs),
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                val rows = remember(diff, filter) { rowsFor(diff, filter) }
                LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                    if (rows.isEmpty()) {
                        item {
                            Text(
                                "Nothing in this category.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(UiScopeTokens.spaceM),
                            )
                        }
                    }
                    items(rows, key = { it.key }) { row ->
                        DiffRow(
                            row = row,
                            selected = row.beforeNode === selectedBefore && row.afterNode === selectedAfter,
                            onClick = {
                                selectedBefore = row.beforeNode
                                selectedAfter = row.afterNode
                            },
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxSize()) {
                        DiffCanvas(
                            capture = beforeCapture,
                            selected = selectedBefore,
                            marks = diff.removed.map { it to Color(230, 57, 70) },
                            title = "Before",
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        DiffCanvas(
                            capture = afterCapture,
                            selected = selectedAfter,
                            marks = diff.added.map { it to Color(42, 157, 93) },
                            title = "After",
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** One browsable result line: a matched pair (with its field changes) or a single added/removed node. */
internal data class DiffRow(
    val key: String,
    val title: String,
    val detail: String,
    val beforeNode: ElementNode?,
    val afterNode: ElementNode?,
)

internal fun rowsFor(diff: DiffResult, filter: DiffFilter): List<DiffRow> {
    val pairs = when (filter) {
        DiffFilter.ALL, DiffFilter.CHANGED, DiffFilter.MOVED -> diff.pairs
            .filter {
                when (filter) {
                    DiffFilter.CHANGED -> it.changes.isNotEmpty()
                    DiffFilter.MOVED -> it.pass == MatchPass.BOUNDS
                    else -> true
                }
            }
            .map { pair ->
                DiffRow(
                    key = "pair-" + pair.before.path,
                    title = (pair.before.identifier ?: pair.before.className)
                        .toString() + if (pair.pass == MatchPass.BOUNDS) " (moved)" else "",
                    detail = pair.changes.joinToString("; ") { it.field + ": " + (it.before ?: "-") + " -> " + (it.after ?: "-") }
                        .ifEmpty { "matched via ${pair.pass.name.lowercase()}" },
                    beforeNode = pair.before,
                    afterNode = pair.after,
                )
            }
        else -> emptyList()
    }
    val added = if (filter == DiffFilter.ALL || filter == DiffFilter.ADDED) {
        diff.added.map { DiffRow("added-" + it.path, labelOf(it), "added", null, it) }
    } else {
        emptyList()
    }
    val removed = if (filter == DiffFilter.ALL || filter == DiffFilter.REMOVED) {
        diff.removed.map { DiffRow("removed-" + it.path, labelOf(it), "removed", it, null) }
    } else {
        emptyList()
    }
    return pairs + added + removed
}

private fun labelOf(node: ElementNode): String =
    listOfNotNull(node.identifier, node.name, node.text, node.className).firstOrNull() ?: node.className

@Composable
private fun DiffRow(row: DiffRow, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = UiScopeTokens.spaceM, vertical = UiScopeTokens.spaceS)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                row.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A snapshot/live picker as an outlined button with a dropdown (plan P6-2: "two snapshots or live vs snapshot"). */
@Composable
private fun SourcePicker(
    label: String,
    current: CompareSource?,
    liveCapture: Capture?,
    snapshots: List<SnapshotMeta>,
    onPick: (CompareSource) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(
                    when (current) {
                        CompareSource.Live -> "Live capture"
                        is CompareSource.Snapshot -> current.meta.title
                        null -> "Pick..."
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(if (liveCapture == null) "Live capture (nothing on screen)" else "Live capture") },
                    enabled = liveCapture != null,
                    onClick = {
                        open = false
                        onPick(CompareSource.Live)
                    },
                )
                for (meta in snapshots) {
                    DropdownMenuItem(
                        text = { Text("${meta.title} (${listTime.format(meta.capturedAt)})") },
                        onClick = {
                            open = false
                            onPick(CompareSource.Snapshot(meta))
                        },
                    )
                }
            }
        }
    }
}

/**
 * One side-by-side screenshot with the diff's coloured boxes (plan P6-2): red for removed (Before), green for
 * added (After), and the selected element in the theme colour. Decoding happens off the UI thread.
 */
@Composable
private fun DiffCanvas(
    capture: Capture?,
    selected: ElementNode?,
    marks: List<Pair<ElementNode, Color>>,
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = UiScopeTokens.spaceM))
        val image by produceState<ImageBitmap?>(null, capture?.screenshot?.file) {
            val file = capture?.screenshot?.file
            value = if (file == null) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        javax.imageio.ImageIO.read(java.io.File(file))?.toComposeImageBitmap()
                    } catch (t: Throwable) {
                        compareLogger.warning("the screenshot could not be decoded for the diff view: " + t.javaClass.simpleName)
                        null
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            Canvas(Modifier.fillMaxSize()) {
                val cap = capture ?: return@Canvas
                val viewport = CaptureViewport.fit(
                    cap.screen,
                    width.toDouble(),
                    height.toDouble(),
                )
                val screen = cap.screen
                val bitmap = image
                if (bitmap != null && screen.width > 0 && screen.height > 0) {
                    drawImage(
                        bitmap,
                        dstOffset = androidx.compose.ui.unit.IntOffset(
                            viewport.panX.toFloat().toInt(),
                            viewport.panY.toFloat().toInt(),
                        ),
                        dstSize = androidx.compose.ui.unit.IntSize(
                            (screen.width * viewport.effectiveScale).toInt().coerceAtLeast(1),
                            (screen.height * viewport.effectiveScale).toInt().coerceAtLeast(1),
                        ),
                    )
                }
                val accent = Color(230, 57, 70)
                for ((node, color) in marks) {
                    viewport.toCanvas(node.bounds).let { rect ->
                        drawRect(color, topLeft = Offset(rect.left.toFloat(), rect.top.toFloat()), size = Size(rect.width.toFloat(), rect.height.toFloat()), style = Stroke(width = 3f))
                    }
                }
                selected?.let { node ->
                    viewport.toCanvas(node.bounds).let { rect ->
                        drawRect(accent, topLeft = Offset(rect.left.toFloat(), rect.top.toFloat()), size = Size(rect.width.toFloat(), rect.height.toFloat()), style = Stroke(width = 3f))
                    }
                }
            }
        }
    }
}

/** The diff as JSON (machine-readable export, plan P6-2). */
internal fun diffAsJson(result: DiffResult): ByteArray = buildString {
    append("{\"pairs\":[")
    result.pairs.joinTo(this, ",") { pair ->
        "{\"before\":\"" + JsonWriter.string(pair.before.path.toString()) + "\"" +
            ",\"after\":\"" + JsonWriter.string(pair.after.path.toString()) + "\"" +
            ",\"pass\":\"" + pair.pass.name + "\"" +
            ",\"changes\":[" + pair.changes.joinToString(",") { change ->
                "{\"field\":\"" + JsonWriter.string(change.field) + "\",\"before\":" +
                    (change.before?.let { JsonWriter.string(it) } ?: "null") + ",\"after\":" +
                    (change.after?.let { JsonWriter.string(it) } ?: "null") + "}"
            } + "]}"
    }
    append("],\"added\":[")
    result.added.joinTo(this, ",") { JsonWriter.string(it.path.toString()) }
    append("],\"removed\":[")
    result.removed.joinTo(this, ",") { JsonWriter.string(it.path.toString()) }
    append("]}")
}.toByteArray(Charsets.UTF_8)

/** The diff as a human-readable text outline (plan P6-2). */
internal fun diffAsText(result: DiffResult): ByteArray = buildString {
    appendLine("UIScope diff")
    appendLine("matched: ${result.pairs.size}, added: ${result.added.size}, removed: ${result.removed.size}")
    for (pair in result.pairs) {
        appendLine("CHANGED ${pair.before.path} (${pair.pass.name.lowercase()})")
        for (change in pair.changes) {
            appendLine("  ${change.field}: ${change.before ?: "-"} -> ${change.after ?: "-"}")
        }
    }
    for (added in result.added) appendLine("ADDED ${added.path} ${labelOf(added)}")
    for (removed in result.removed) appendLine("REMOVED ${removed.path} ${labelOf(removed)}")
}.toByteArray(Charsets.UTF_8)
