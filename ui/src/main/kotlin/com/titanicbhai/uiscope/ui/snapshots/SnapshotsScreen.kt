package com.titanicbhai.uiscope.ui.snapshots

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.snapshot.LoadedSnapshot
import com.titanicbhai.uiscope.snapshot.SnapshotMeta
import com.titanicbhai.uiscope.snapshot.SnapshotSort
import com.titanicbhai.uiscope.snapshot.SnapshotStore
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** The shell side of the Snapshots screen: bundle export/import (engine), the native file dialogs and the toasts. */
data class SnapshotCallbacks(
    val onMessage: (String) -> Unit = {},
    /** Opens the snapshot in the offline Inspector (the shell switches to the Inspect screen). */
    val onOpenInInspector: (LoadedSnapshot) -> Unit = {},
    val exportBundle: suspend (id: String, target: Path) -> Outcome<Unit> =
        { _, _ -> Outcome.Failure(CodedError(ErrorCodes.UNEXPECTED, "bundles are unavailable")) },
    val importBundle: suspend (source: Path) -> Outcome<SnapshotMeta> =
        { _ -> Outcome.Failure(CodedError(ErrorCodes.UNEXPECTED, "bundles are unavailable")) },
    /** The native save dialog, on the event thread like the adb browser; null means the user cancelled. */
    val pickExportFile: (suggestedName: String) -> Path? = { null },
    val pickImportFile: () -> Path? = { null },
)

private val listTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${(bytes + 512) / 1_000} kB"
    else -> "$bytes B"
}

/**
 * The Snapshots screen (plan P5-1, 4.7): search, sort, list; selecting shows the preview with Open in Inspector,
 * Rename/Note, Pin, Export .uiscope (first export shows the one-line privacy notice) and Delete with confirmation.
 * Import is a button (drag-and-drop arrives with the desktop shell work). Everything file-backed runs through the
 * [SnapshotStore] port on I/O dispatchers - nothing blocks composition.
 */
@Composable
fun SnapshotsScreen(
    store: SnapshotStore?,
    callbacks: SnapshotCallbacks,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var sort by remember { mutableStateOf(SnapshotSort.NEWEST) }
    var revision by remember { mutableStateOf(0) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<SnapshotMeta?>(null) }
    var deleting by remember { mutableStateOf<SnapshotMeta?>(null) }
    var privacyNotice by remember { mutableStateOf<SnapshotMeta?>(null) }

    val list = produceState<List<SnapshotMeta>>(emptyList(), store, query.text, sort, revision) {
        value = when (val result = store?.list(query.text.ifBlank { null }, sort)) {
            is Outcome.Success -> result.value
            else -> emptyList()
        }
    }
    val preview = produceState<LoadedSnapshot?>(null, store, selectedId, revision) {
        value = when {
            selectedId == null -> null
            else -> when (val loaded = store?.load(selectedId!!)) {
                is Outcome.Success -> loaded.value
                else -> null
            }
        }
    }

    if (store == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Snapshots are unavailable this session (the database did not open).",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    Column(modifier.fillMaxSize().testTag("uiscope.snapshots")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = UiScopeTokens.spaceM, vertical = UiScopeTokens.spaceS),
            horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("snapshots.search"),
            )
            SortPicker(sort) { sort = it }
            OutlinedButton(onClick = {
                val source = callbacks.pickImportFile()
                if (source != null) {
                    scope.launch {
                        when (val imported = callbacks.importBundle(source)) {
                            is Outcome.Success -> {
                                callbacks.onMessage("Imported \"${imported.value.title}\".")
                                revision++
                            }
                            is Outcome.Failure -> callbacks.onMessage("The bundle was rejected (${imported.error.code}).")
                        }
                    }
                }
            }) { Text("Import...") }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                if (list.value.isEmpty()) {
                    item {
                        Text(
                            "No snapshots yet - capture something and press Save snapshot (Ctrl+S) in the Inspector.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(UiScopeTokens.spaceL),
                        )
                    }
                }
                items(list.value, key = { it.id }) { meta ->
                    SnapshotRow(
                        meta = meta,
                        selected = meta.id == selectedId,
                        onClick = { selectedId = meta.id },
                        onTogglePin = {
                            scope.launch {
                                store.update(meta.id, pinned = !meta.pinned)
                                revision++
                            }
                        },
                    )
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.width(340.dp).fillMaxHeight()) {
                val loaded = preview.value
                if (loaded == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Select a snapshot to preview it.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(UiScopeTokens.spaceL),
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(UiScopeTokens.spaceM), verticalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                        Text(loaded.meta.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (loaded.meta.note.isNotBlank()) {
                            Text(loaded.meta.note, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${loaded.meta.targetLabel} - ${loaded.meta.appLabel}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            listTime.format(loaded.meta.capturedAt) + " - ${loaded.meta.nodeCount} elements - " + sizeLabel(loaded.meta.bytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { callbacks.onOpenInInspector(loaded) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Open in Inspector")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                            OutlinedButton(onClick = { renaming = loaded.meta }) { Text("Rename/Note...") }
                            OutlinedButton(onClick = { privacyNotice = loaded.meta }) { Text("Export...") }
                        }
                        OutlinedButton(onClick = { deleting = loaded.meta }, modifier = Modifier.fillMaxWidth()) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }

    renaming?.let { meta ->
        var title by remember(meta.id) { mutableStateOf(meta.title) }
        var note by remember(meta.id) { mutableStateOf(meta.note) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename / note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        store.update(meta.id, title = title.trim().ifBlank { null }, note = note)
                        revision++
                        callbacks.onMessage("Snapshot updated.")
                    }
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    privacyNotice?.let { meta ->
        AlertDialog(
            onDismissRequest = { privacyNotice = null },
            title = { Text("Export this snapshot?") },
            text = { Text("Snapshots contain whatever was on the screen when it was captured.") },
            confirmButton = {
                TextButton(onClick = {
                    privacyNotice = null
                    scope.launch {
                        val target = callbacks.pickExportFile(meta.title.filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "snapshot" } + ".uiscope")
                        if (target != null) {
                            when (val exported = callbacks.exportBundle(meta.id, target)) {
                                is Outcome.Success -> callbacks.onMessage("Exported to ${target.fileName}.")
                                is Outcome.Failure -> callbacks.onMessage("The export failed (${exported.error.code}).")
                            }
                        }
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { privacyNotice = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { meta ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${meta.title}\"?") },
            text = { Text("The tree, the screenshot and the files on disk are removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        when (val deleted = store.delete(meta.id)) {
                            is Outcome.Success -> callbacks.onMessage("Snapshot deleted.")
                            is Outcome.Failure -> callbacks.onMessage("The deletion failed (${deleted.error.code}).")
                        }
                        if (selectedId == meta.id) selectedId = null
                        revision++
                    }
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SortPicker(sort: SnapshotSort, onSort: (SnapshotSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(sort.name.lowercase().replaceFirstChar { it.uppercase() }) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in SnapshotSort.entries) {
                DropdownMenuItem(
                    text = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        open = false
                        onSort(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun SnapshotRow(
    meta: SnapshotMeta,
    selected: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = UiScopeTokens.spaceM, vertical = UiScopeTokens.spaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(meta.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${meta.targetLabel} - ${meta.appLabel} - ${listTime.format(meta.capturedAt)} - ${meta.nodeCount} elements - " + sizeLabel(meta.bytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (meta.note.isNotBlank()) {
            Text("note", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = UiScopeTokens.spaceS))
        }
        TextButton(onClick = onTogglePin, modifier = Modifier.padding(start = UiScopeTokens.spaceS)) {
            Text(if (meta.pinned) "Unpin" else "Pin")
        }
    }
}
