package com.titanicbhai.uiscope.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepository() }

    var adbPath by remember { mutableStateOf("") }
    var theme by remember { mutableStateOf("SYSTEM") }
    var pollingInterval by remember { mutableStateOf("2000") }
    var autoRefresh by remember { mutableStateOf(true) }
    var defaultExport by remember { mutableStateOf("JSON") }
    var historyLimit by remember { mutableStateOf("50") }
    var autoSave by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            adbPath = repo.get(SettingsRepository.ADB_PATH, "") ?: ""
            theme = repo.get(SettingsRepository.THEME, "SYSTEM") ?: "SYSTEM"
            pollingInterval = repo.getLong(SettingsRepository.POLLING_INTERVAL_MS, 2000L).toString()
            autoRefresh = repo.getBoolean(SettingsRepository.AUTO_REFRESH, true)
            defaultExport = repo.get(SettingsRepository.DEFAULT_EXPORT_FORMAT, "JSON") ?: "JSON"
            historyLimit = repo.getLong(SettingsRepository.HISTORY_LIMIT, 50L).toString()
            autoSave = repo.getBoolean("auto_save_sessions", true)
        }
        loaded = true
    }

    fun save() {
        scope.launch {
            withContext(Dispatchers.IO) {
                repo.set(SettingsRepository.ADB_PATH, adbPath)
                repo.set(SettingsRepository.THEME, theme)
                repo.setLong(SettingsRepository.POLLING_INTERVAL_MS, pollingInterval.toLongOrNull() ?: 2000L)
                repo.setBoolean(SettingsRepository.AUTO_REFRESH, autoRefresh)
                repo.set(SettingsRepository.DEFAULT_EXPORT_FORMAT, defaultExport)
                repo.setLong(SettingsRepository.HISTORY_LIMIT, historyLimit.toLongOrNull() ?: 50L)
                repo.setBoolean("auto_save_sessions", autoSave)
            }
        }
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
                TextButton(onClick = {
                    save()
                    onBack()
                }) {
                    Text("← Back", color = colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
                Text("│", color = colorScheme.outline)
                Text(
                    "⚙  Settings",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface
                )
            }
            TextButton(onClick = { save() }) {
                Text("Save", color = colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider(color = colorScheme.outline)

        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsSection(title = "Android Debug Bridge (ADB)") {
                SettingsRowText(
                    label = "ADB path",
                    hint = "Leave blank to auto-detect from PATH and \$ANDROID_HOME",
                    value = adbPath,
                    onValueChange = { adbPath = it }
                )
                Text(
                    "Detection order: \$ANDROID_HOME/platform-tools/adb → \$ANDROID_SDK_ROOT/platform-tools/adb → adb on PATH",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            SettingsSection(title = "Android Inspection") {
                SettingsRowChoice(
                    label = "Screenshot polling interval",
                    options = listOf("500" to "0.5 s", "1000" to "1 s", "2000" to "2 s", "5000" to "5 s"),
                    value = pollingInterval,
                    onValueChange = { pollingInterval = it }
                )
                SettingsRowToggle(
                    label = "Auto-refresh on mode entry",
                    description = "Automatically refresh the device tree when you switch to Android mode",
                    value = autoRefresh,
                    onToggle = { autoRefresh = it }
                )
            }

            SettingsSection(title = "Appearance") {
                SettingsRowChoice(
                    label = "Theme",
                    options = listOf("SYSTEM" to "System default", "LIGHT" to "Light", "DARK" to "Dark"),
                    value = theme,
                    onValueChange = { theme = it }
                )
            }

            SettingsSection(title = "Export") {
                SettingsRowChoice(
                    label = "Default export format",
                    options = listOf("JSON" to "JSON", "XML" to "XML", "OUTLINE" to "Outline (text)"),
                    value = defaultExport,
                    onValueChange = { defaultExport = it }
                )
            }

            SettingsSection(title = "Session History") {
                SettingsRowChoice(
                    label = "Session history retention",
                    options = listOf("10" to "Last 10", "50" to "Last 50", "100" to "Last 100", "0" to "Unlimited"),
                    value = historyLimit,
                    onValueChange = { historyLimit = it }
                )
                SettingsRowToggle(
                    label = "Auto-save sessions",
                    description = "Automatically save each inspection to session history",
                    value = autoSave,
                    onToggle = { autoSave = it }
                )
            }

            SettingsSection(title = "Android Screenshot") {
                SettingsRowChoice(
                    label = "Screenshot quality",
                    options = listOf("FULL" to "Full resolution", "SCALED" to "Scaled (faster)"),
                    value = repo.get("screenshot_quality", "FULL") ?: "FULL",
                    onValueChange = { scope.launch { withContext(Dispatchers.IO) { repo.set("screenshot_quality", it) } } }
                )
                Text(
                    "Full resolution produces better quality; Scaled is faster on slow ADB connections.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            SettingsSection(title = "System") {
                SettingsRowToggle(
                    label = "Launch at system startup",
                    description = "Start UIScope hidden in the tray when you log in. (Requires restart to take effect.)",
                    value = repo.getBoolean("launch_at_startup", false),
                    onToggle = { scope.launch { withContext(Dispatchers.IO) { repo.setBoolean("launch_at_startup", it) } } }
                )
                SettingsRowToggle(
                    label = "Minimize to system tray",
                    description = "When you close the window, UIScope stays running in the system tray / menu bar.",
                    value = repo.getBoolean("minimize_to_tray", true),
                    onToggle = { scope.launch { withContext(Dispatchers.IO) { repo.setBoolean("minimize_to_tray", it) } } }
                )
            }

            SettingsSection(title = "Keyboard Shortcuts (read-only)") {
                val shortcuts = listOf(
                    "Alt + Shift + P" to "Pick mode (PC)",
                    "R" to "Refresh",
                    "Ctrl + F" to "Search / filter tree",
                    "Ctrl + E" to "Export",
                    "Esc" to "Cancel / close overlay"
                )
                shortcuts.forEach { (keys, action) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(action, style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurface)
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = colorScheme.surfaceVariant
                        ) {
                            Text(
                                keys,
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Text(
                    "Global hotkeys (Phase 4): jnativehook integration planned.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            SettingsSection(title = "About") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("UIScope — See what your UI is made of.", style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurface)
                    Text("Fully offline. No accounts, no telemetry, nothing leaves your machine.", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
                    Text("MIT License — Open source on GitHub.", style = MaterialTheme.typography.bodySmall, color = colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun SettingsRowText(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = colorScheme.onSurface)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(8.dp)
        )
    }
}

@Composable
private fun SettingsRowChoice(
    label: String,
    options: List<Pair<String, String>>,
    value: String,
    onValueChange: (String) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { (key, display) ->
                val selected = key == value
                FilterChip(
                    selected = selected,
                    onClick = { onValueChange(key) },
                    label = { Text(display, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

@Composable
private fun SettingsRowToggle(
    label: String,
    description: String,
    value: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        Switch(
            checked = value,
            onCheckedChange = onToggle
        )
    }
}
