package com.titanicbhai.uiscope.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanicbhai.uiscope.android.AdbDevice
import com.titanicbhai.uiscope.android.AdbManager
import com.titanicbhai.uiscope.android.UiAutomatorParser
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.WatchConditionType
import com.titanicbhai.uiscope.model.WatchRule
import com.titanicbhai.uiscope.theme.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun WatchScreen(adbManager: AdbManager, onBack: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var rules by remember { mutableStateOf<List<WatchRule>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var monitorLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var devices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<AdbDevice?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    val activeJobs = remember { mutableStateMapOf<String, Job>() }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    fun ts() = "[${timeFmt.format(Date())}]"

    fun flattenNodes(nodes: List<ElementNode>): List<ElementNode> {
        val out = mutableListOf<ElementNode>()
        fun visit(n: ElementNode) { out.add(n); n.children.forEach { visit(it) } }
        nodes.forEach { visit(it) }
        return out
    }

    fun matchesRule(node: ElementNode, rule: WatchRule): Boolean {
        val resId     = rule.targetResourceId
        val className = rule.targetClassName
        val text      = rule.targetText
        if (resId != null && node.resourceId != resId) return false
        if (className != null && !node.className.contains(className, ignoreCase = true)) return false
        if (rule.conditionType == WatchConditionType.TEXT_MATCHES && text != null) {
            return node.text?.contains(text, ignoreCase = true) == true
        }
        return true
    }

    fun stopRule(rule: WatchRule) {
        activeJobs[rule.id]?.cancel()
        activeJobs.remove(rule.id)
        rules = rules.map { if (it.id == rule.id) it.copy(isActive = false) else it }
        monitorLog = monitorLog + "${ts()} ⏹ Stopped: ${rule.label}"
    }

    fun startRule(rule: WatchRule) {
        val device = selectedDevice ?: run {
            monitorLog = monitorLog + "${ts()} ⚠ No device selected — connect an Android device first"
            return
        }
        rules = rules.map { if (it.id == rule.id) it.copy(isActive = true) else it }
        monitorLog = monitorLog + "${ts()} ▶ Started: ${rule.label}  [${device.serial}]"

        activeJobs[rule.id] = scope.launch(Dispatchers.IO) {
            val parser = UiAutomatorParser()
            var prevPresent: Boolean? = null
            while (isActive) {
                try {
                    val xml = adbManager.dumpUiHierarchy(device.serial)
                    val nodes = flattenNodes(parser.parse(xml))
                    val present = nodes.any { matchesRule(it, rule) }
                    val t = ts()
                    when (rule.conditionType) {
                        WatchConditionType.ELEMENT_APPEARS -> {
                            if (present && prevPresent != true) {
                                withContext(Dispatchers.Main) {
                                    monitorLog = monitorLog + "$t ✓ APPEARS — ${rule.label}"
                                }
                            }
                        }
                        WatchConditionType.ELEMENT_DISAPPEARS -> {
                            if (!present && prevPresent == true) {
                                withContext(Dispatchers.Main) {
                                    monitorLog = monitorLog + "$t ✓ DISAPPEARS — ${rule.label}"
                                }
                            }
                        }
                        WatchConditionType.TEXT_MATCHES -> {
                            if (present && prevPresent != true) {
                                withContext(Dispatchers.Main) {
                                    monitorLog = monitorLog + "$t ✓ TEXT MATCHED — ${rule.label}"
                                }
                            } else if (!present && prevPresent == true) {
                                withContext(Dispatchers.Main) {
                                    monitorLog = monitorLog + "$t ✗ TEXT GONE — ${rule.label}"
                                }
                            }
                        }
                    }
                    prevPresent = present
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        monitorLog = monitorLog + "${ts()} ⚠ ${rule.label}: ${e.message?.take(60) ?: "error"}"
                    }
                }
                delay(rule.pollIntervalMs)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
        }
    }

    LaunchedEffect(Unit) {
        isScanning = true
        devices = withContext(Dispatchers.IO) {
            runCatching { adbManager.listDevices() }.getOrDefault(emptyList())
        }
        selectedDevice = devices.firstOrNull()
        isScanning = false
    }

    Column(modifier = Modifier.fillMaxSize().background(colorScheme.background)) {
        // ── Top bar ─────────────────────────────────────────────────────────
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
                    "👁  Watch Mode",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    isScanning -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    devices.isNotEmpty() -> WatchDevicePicker(
                        devices = devices,
                        selected = selectedDevice,
                        onSelect = { selectedDevice = it }
                    )
                    else -> Text(
                        "No Android devices",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("+ Add Rule", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.4f))

        // ── Body ─────────────────────────────────────────────────────────────
        if (rules.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("👁", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "No watch rules yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Add a rule to monitor for a specific element appearing,\n" +
                        "disappearing, or matching a text value on a connected device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                    Button(onClick = { showAddDialog = true }) { Text("+ Add First Rule") }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rules, key = { it.id }) { rule ->
                        WatchRuleCard(
                            rule = rule,
                            onToggleActive = { toggled ->
                                if (toggled.isActive) stopRule(toggled) else startRule(toggled)
                            },
                            onDelete = { deleted ->
                                if (deleted.isActive) stopRule(deleted)
                                rules = rules.filter { it.id != deleted.id }
                            }
                        )
                    }
                }

                // ── Monitor log ───────────────────────────────────────────────
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Monitor Log",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { monitorLog = emptyList() },
                            modifier = Modifier.height(24.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text("Clear", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (monitorLog.isEmpty()) {
                        Text(
                            "Waiting for events…",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            fontSize = 10.sp
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(monitorLog.reversed()) { entry ->
                                val color = when {
                                    "✓" in entry -> AccentGreen
                                    "⚠" in entry -> AccentRed
                                    "▶" in entry -> AccentBlue
                                    "⏹" in entry -> colorScheme.onSurfaceVariant
                                    else          -> colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                }
                                Text(
                                    entry,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = color,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddWatchRuleDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { newRule ->
                rules = rules + newRule
                showAddDialog = false
            }
        )
    }
}

// ── Device picker ─────────────────────────────────────────────────────────────

@Composable
private fun WatchDevicePicker(
    devices: List<AdbDevice>,
    selected: AdbDevice?,
    onSelect: (AdbDevice) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
        ) {
            Text(
                selected?.let { it.model ?: it.serial } ?: "Select device",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            devices.forEach { d ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(d.model ?: d.serial, style = MaterialTheme.typography.bodySmall)
                            Text(d.serial, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { onSelect(d); expanded = false }
                )
            }
        }
    }
}

// ── Rule card ─────────────────────────────────────────────────────────────────

@Composable
private fun WatchRuleCard(
    rule: WatchRule,
    onToggleActive: (WatchRule) -> Unit,
    onDelete: (WatchRule) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val statusColor = if (rule.isActive) AccentGreen else colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(8.dp).background(statusColor, RoundedCornerShape(50)))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rule.label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                val condDesc = when (rule.conditionType) {
                    WatchConditionType.ELEMENT_APPEARS ->
                        "Appears: ${rule.targetResourceId ?: rule.targetClassName ?: rule.targetText ?: "any"}"
                    WatchConditionType.ELEMENT_DISAPPEARS ->
                        "Disappears: ${rule.targetResourceId ?: rule.targetClassName ?: "any"}"
                    WatchConditionType.TEXT_MATCHES ->
                        "Text matches: \"${rule.targetText ?: ""}\""
                }
                Text(
                    condDesc,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                    color = colorScheme.onSurfaceVariant
                )
                Text(
                    "Poll every ${rule.pollIntervalMs / 1000f}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 10.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { onToggleActive(rule) },
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (rule.isActive) AccentRed else AccentGreen
                    )
                ) {
                    Text(
                        if (rule.isActive) "⏹ Stop" else "▶ Start",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp
                    )
                }
                TextButton(
                    onClick = { onDelete(rule) },
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("✕", color = AccentRed, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
                }
            }
        }
    }
}

// ── Add rule dialog ───────────────────────────────────────────────────────────

@Composable
private fun AddWatchRuleDialog(onDismiss: () -> Unit, onAdd: (WatchRule) -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    var label by remember { mutableStateOf("") }
    var conditionType by remember { mutableStateOf(WatchConditionType.ELEMENT_APPEARS) }
    var targetResId by remember { mutableStateOf("") }
    var targetText by remember { mutableStateOf("") }
    var targetClass by remember { mutableStateOf("") }
    var pollIntervalMs by remember { mutableStateOf(2000L) }
    var expandedCondition by remember { mutableStateOf(false) }
    var expandedInterval by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colorScheme.surface,
        title = {
            Text(
                "Add Watch Rule",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Rule label", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Box {
                    OutlinedButton(onClick = { expandedCondition = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            conditionType.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    DropdownMenu(expanded = expandedCondition, onDismissRequest = { expandedCondition = false }) {
                        WatchConditionType.entries.forEach { ct ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        ct.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                onClick = { conditionType = ct; expandedCondition = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = targetResId,
                    onValueChange = { targetResId = it },
                    label = { Text("Resource ID (optional)", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    placeholder = { Text("com.example:id/button", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (conditionType == WatchConditionType.TEXT_MATCHES) {
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { targetText = it },
                        label = { Text("Target text", style = MaterialTheme.typography.labelSmall) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = targetClass,
                    onValueChange = { targetClass = it },
                    label = { Text("Class name (optional)", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    placeholder = { Text("android.widget.Button", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth()
                )
                Box {
                    OutlinedButton(onClick = { expandedInterval = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Poll every ${pollIntervalMs / 1000f}s", style = MaterialTheme.typography.bodySmall)
                    }
                    DropdownMenu(expanded = expandedInterval, onDismissRequest = { expandedInterval = false }) {
                        listOf(500L to "0.5s", 1000L to "1s", 2000L to "2s", 5000L to "5s").forEach { (ms, lbl) ->
                            DropdownMenuItem(
                                text = { Text(lbl, style = MaterialTheme.typography.bodySmall) },
                                onClick = { pollIntervalMs = ms; expandedInterval = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (label.isBlank()) return@Button
                    onAdd(
                        WatchRule(
                            id = UUID.randomUUID().toString(),
                            label = label.trim(),
                            conditionType = conditionType,
                            targetText = targetText.takeIf { it.isNotBlank() },
                            targetResourceId = targetResId.takeIf { it.isNotBlank() },
                            targetClassName = targetClass.takeIf { it.isNotBlank() },
                            pollIntervalMs = pollIntervalMs,
                            isActive = false,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                },
                enabled = label.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
