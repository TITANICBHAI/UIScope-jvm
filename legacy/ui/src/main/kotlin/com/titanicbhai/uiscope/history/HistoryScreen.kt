package com.titanicbhai.uiscope.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanicbhai.uiscope.export.TreeExporter
import com.titanicbhai.uiscope.model.InspectionMode
import com.titanicbhai.uiscope.model.Session
import com.titanicbhai.uiscope.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

private val DateFmt = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault())

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val sessionRepo = remember { SessionRepository() }

    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedSession by remember { mutableStateOf<Session?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var deleteConfirm by remember { mutableStateOf<Session?>(null) }

    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) { sessionRepo.getAll() }
        isLoading = false
    }

    val filtered = sessions.filter { s ->
        if (searchQuery.isBlank()) true
        else listOfNotNull(s.appName, s.packageName, s.deviceName, s.mode.name)
            .any { it.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().background(colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(colorScheme.surface)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) {
                    Text("← Back", color = colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
                Text("│", color = colorScheme.outline)
                Text(
                    "📋  Session History",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface
                )
                Text(
                    "${sessions.size} sessions",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter sessions…", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.width(220.dp).height(40.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(8.dp)
            )
        }
        HorizontalDivider(color = colorScheme.outline)

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (filtered.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("📋", fontSize = 48.sp)
                    Text(
                        if (sessions.isEmpty()) "No sessions yet" else "No sessions match \"$searchQuery\"",
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                    if (sessions.isEmpty()) {
                        Text(
                            "Start inspecting an app — UIScope saves each session automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .width(380.dp)
                        .fillMaxHeight()
                        .background(colorScheme.surface),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filtered, key = { it.id }) { session ->
                        SessionListItem(
                            session = session,
                            isSelected = session.id == selectedSession?.id,
                            onClick = { selectedSession = session },
                            onDelete = { deleteConfirm = session }
                        )
                    }
                }

                VerticalDivider(color = colorScheme.outline)

                Box(modifier = Modifier.fillMaxSize().background(colorScheme.background)) {
                    val sel = selectedSession
                    if (sel == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Select a session to view its tree",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    } else {
                        SessionDetail(
                            session = sel,
                            onExportJson = {
                                scope.launch {
                                    exportSessionJson(sel)
                                }
                            },
                            onCopyTree = {
                                val sel2 = StringSelection(sel.treeJson)
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel2, null)
                            }
                        )
                    }
                }
            }
        }
    }

    if (deleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text("Delete Session?") },
            text = {
                Text("Delete the session from ${DateFmt.format(Date(deleteConfirm!!.timestamp))}?\n\nThis cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = deleteConfirm!!
                    scope.launch {
                        withContext(Dispatchers.IO) { sessionRepo.delete(toDelete.id) }
                        sessions = sessions.filter { it.id != toDelete.id }
                        if (selectedSession?.id == toDelete.id) selectedSession = null
                        deleteConfirm = null
                    }
                }) { Text("Delete", color = colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SessionListItem(
    session: Session,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.4f)
                else colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (session.mode == InspectionMode.PC) "🖥" else "📱",
                    fontSize = 13.sp
                )
                Text(
                    session.appName ?: session.packageName ?: "Unknown App",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    DateFmt.format(Date(session.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                session.deviceName?.let {
                    Text("·", color = colorScheme.outline)
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                }
            }
        }
        TextButton(
            onClick = onDelete,
            contentPadding = PaddingValues(4.dp)
        ) {
            Text("✕", fontSize = 12.sp, color = colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
    }
    HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.15f))
}

@Composable
private fun SessionDetail(
    session: Session,
    onExportJson: () -> Unit,
    onCopyTree: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                session.appName ?: session.packageName ?: "Unknown App",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = colorScheme.onBackground
            )
            Text(
                DateFmt.format(Date(session.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Mode", if (session.mode == InspectionMode.PC) "PC Inspector" else "Android Inspector")
                session.appName?.let { DetailRow("App", it) }
                session.packageName?.let { DetailRow("Package", it) }
                session.deviceName?.let { DetailRow("Device", it) }
                session.screenshotPath?.let { DetailRow("Screenshot", it) }
                DetailRow("Session ID", session.id)
            }
        }

        if (session.screenshotPath != null && File(session.screenshotPath).exists()) {
            Text(
                "📷 Screenshot saved",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.primary
            )
        }

        Surface(
            shape = MaterialTheme.shapes.small,
            color = colorScheme.surface,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Tree JSON preview", style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurfaceVariant)
                }
                Text(
                    session.treeJson.take(2000) + if (session.treeJson.length > 2000) "\n…" else "",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 10.sp
                    ),
                    color = colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onExportJson) { Text("Export JSON…") }
            OutlinedButton(onClick = onCopyTree) { Text("Copy Tree JSON") }
        }
    }
}

@Composable
private fun DetailRow(key: String, value: String) {
    val colorScheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$key:",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private suspend fun exportSessionJson(session: Session) {
    withContext(Dispatchers.IO) {
        try {
            val chooser = JFileChooser()
            val safeName = (session.appName ?: session.packageName ?: "session")
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            chooser.selectedFile = File("uiscope_${safeName}.json")
            chooser.fileFilter = FileNameExtensionFilter("JSON files", "json")
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                val file = chooser.selectedFile
                val target = if (file.extension.lowercase() == "json") file else File("${file.absolutePath}.json")
                target.writeText(session.treeJson)
            }
        } catch (_: Exception) {}
    }
}
