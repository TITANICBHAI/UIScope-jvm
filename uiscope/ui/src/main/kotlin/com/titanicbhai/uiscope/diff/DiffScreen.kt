package com.titanicbhai.uiscope.diff

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanicbhai.uiscope.model.Session
import com.titanicbhai.uiscope.repository.SessionRepository
import com.titanicbhai.uiscope.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class DiffItem(
    val nodeId: String,
    val label: String,
    val status: DiffStatus
)

private enum class DiffStatus { ADDED, REMOVED, UNCHANGED }

private fun extractIds(treeJson: String): Map<String, String> {
    // Extract nodes from JSON by parsing "id":"..." and "name":"..." patterns
    val result = mutableMapOf<String, String>()
    try {
        val idPattern = Regex(""""id"\s*:\s*"([^"]+)"""")
        val namePattern = Regex(""""name"\s*:\s*"([^"]+)"""")
        val classPattern = Regex(""""className"\s*:\s*"([^"]+)"""")
        val entries = treeJson.split(Regex("""\},\s*\{"""))
        for (entry in entries) {
            val id = idPattern.find(entry)?.groupValues?.get(1) ?: continue
            val name = namePattern.find(entry)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            val cls = classPattern.find(entry)?.groupValues?.get(1)?.substringAfterLast('.')
            result[id] = name ?: cls ?: id
        }
    } catch (_: Exception) {}
    return result
}

private fun computeDiff(idsA: Map<String, String>, idsB: Map<String, String>): List<DiffItem> {
    val all = mutableListOf<DiffItem>()
    idsB.forEach { (id, label) ->
        if (id !in idsA) all.add(DiffItem(id, label, DiffStatus.ADDED))
    }
    idsA.forEach { (id, label) ->
        if (id !in idsB) all.add(DiffItem(id, label, DiffStatus.REMOVED))
    }
    return all.sortedBy { it.status.name }
}

@Composable
fun DiffScreen(onBack: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val sessionRepo = remember { SessionRepository() }

    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var sessionA by remember { mutableStateOf<Session?>(null) }
    var sessionB by remember { mutableStateOf<Session?>(null) }
    var diffItems by remember { mutableStateOf<List<DiffItem>?>(null) }
    var isComputing by remember { mutableStateOf(false) }
    var expandedA by remember { mutableStateOf(false) }
    var expandedB by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) { sessionRepo.getAll() }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().background(colorScheme.background)) {
        // Top bar
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
                    "⚖  Diff Mode",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface
                )
            }
            Text(
                "Compare two captured sessions side by side",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
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
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

        // Session pickers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Session A selector
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Session A (before)",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Box {
                    OutlinedButton(
                        onClick = { expandedA = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            sessionA?.let { "${it.appName ?: it.packageName ?: "Session"} — ${it.mode.name}" } ?: "Select session…",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                    DropdownMenu(
                        expanded = expandedA,
                        onDismissRequest = { expandedA = false }
                    ) {
                        sessions.forEach { s ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(s.appName ?: s.packageName ?: "Session", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "${s.mode.name} · ${s.deviceName ?: ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = { sessionA = s; expandedA = false; diffItems = null }
                            )
                        }
                    }
                }
            }

            // Session B selector
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Session B (after)",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Box {
                    OutlinedButton(
                        onClick = { expandedB = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            sessionB?.let { "${it.appName ?: it.packageName ?: "Session"} — ${it.mode.name}" } ?: "Select session…",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                    DropdownMenu(
                        expanded = expandedB,
                        onDismissRequest = { expandedB = false }
                    ) {
                        sessions.forEach { s ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(s.appName ?: s.packageName ?: "Session", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "${s.mode.name} · ${s.deviceName ?: ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = { sessionB = s; expandedB = false; diffItems = null }
                            )
                        }
                    }
                }
            }
        }

        // Compute button
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
                        val idsA = extractIds(sa.treeJson ?: "")
                        val idsB = extractIds(sb.treeJson ?: "")
                        diffItems = computeDiff(idsA, idsB)
                        isComputing = false
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

        // Diff results
        val items = diffItems
        if (items == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            val added = items.count { it.status == DiffStatus.ADDED }
            val removed = items.count { it.status == DiffStatus.REMOVED }

            // Summary bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Diff results:", style = MaterialTheme.typography.labelMedium, color = colorScheme.onSurfaceVariant)
                Box(
                    modifier = Modifier
                        .background(AccentGreen.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("+$added added", color = AccentGreen, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                Box(
                    modifier = Modifier
                        .background(AccentRed.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("-$removed removed", color = AccentRed, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
            }

            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✓", style = MaterialTheme.typography.displaySmall, color = AccentGreen)
                        Text("No differences found", style = MaterialTheme.typography.bodyLarge, color = colorScheme.onSurface)
                        Text("Both sessions have identical element structures.", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    items(items) { item ->
                        DiffItemRow(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffItemRow(item: DiffItem) {
    val colorScheme = MaterialTheme.colorScheme
    val (bgColor, borderColor, prefix) = when (item.status) {
        DiffStatus.ADDED -> Triple(AccentGreen.copy(alpha = 0.08f), AccentGreen, "+")
        DiffStatus.REMOVED -> Triple(AccentRed.copy(alpha = 0.08f), AccentRed, "−")
        DiffStatus.UNCHANGED -> Triple(Color.Transparent, colorScheme.outline, " ")
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
            color = borderColor,
            fontSize = 13.sp,
            modifier = Modifier.width(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.label,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                item.nodeId,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                maxLines = 1
            )
        }
        Box(
            modifier = Modifier
                .background(borderColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                item.status.name,
                style = MaterialTheme.typography.labelSmall,
                color = borderColor,
                fontSize = 9.sp
            )
        }
    }
}
