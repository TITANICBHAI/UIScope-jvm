package com.titanicbhai.uiscope.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.AppInfo
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.settings.ExportFormat
import com.titanicbhai.uiscope.settings.LogLevelSetting
import com.titanicbhai.uiscope.settings.Renderer
import com.titanicbhai.uiscope.settings.SettingsStore
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.snapshot.SnapshotStore
import com.titanicbhai.uiscope.snapshot.StorageUsage
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Settings screen (plan P4-8, 4.9): every control applies instantly through the settings store - there is no Save
 * button and no control that does nothing. "Check for updates" hides in the Store build. Windows-section controls arrive
 * with the pick hotkey (P3-6); the ADB Browse/Test/Download buttons arrive with the first-run screen (P4-7).
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    appInfo: AppInfo,
    adbLine: String?,
    snapshots: SnapshotStore?,
    adbDownload: com.titanicbhai.uiscope.ui.firstrun.AdbDownloadActions? = null,
    onBrowseAdb: () -> Unit = {},
    onAdbRecheck: () -> Unit = {},
    diagnostics: () -> String,
    onOpenLogs: () -> Unit,
    onOpenSnapshots: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by settings.settings.collectAsState()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var confirmReset by remember { mutableStateOf(false) }
    var consentText by remember { mutableStateOf<String?>(null) }
    var consentNote by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    val ioScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val storageUsage = produceState<StorageUsage?>(initialValue = null, snapshots) {
        if (snapshots != null) {
            when (val usage = snapshots.usage()) {
                is Outcome.Success -> value = usage.value
                is Outcome.Failure -> value = null
            }
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiScopeTokens.spaceL)) {
        SettingsSection("General") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                Text("Theme", style = MaterialTheme.typography.bodyMedium)
                for (mode in ThemeMode.entries) {
                    AssistChip(
                        onClick = { settings.update { it.copy(theme = mode) } },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            if (!appInfo.isStoreBuild) {
                SettingSwitch("Check for updates on startup", current.checkForUpdates) { enabled ->
                    settings.update { it.copy(checkForUpdates = enabled) }
                }
            }
        }
        SettingsSection("Android") {
            Text("adb: ${adbLine ?: "not found"}", style = MaterialTheme.typography.bodySmall)
            adbDownload?.let { actions ->
                Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                    OutlinedButton(onClick = {
                        when (val consent = actions.consentText()) {
                            is Outcome.Success -> {
                                consentNote = null
                                consentText = consent.value
                            }
                            is Outcome.Failure -> consentNote = "Downloading is not available right now (${consent.error.code})."
                        }
                    }) { Text("Download platform-tools") }
                    OutlinedButton(onClick = onBrowseAdb) { Text("Browse...") }
                    OutlinedButton(onClick = onAdbRecheck) { Text("Re-check") }
                }
                if (downloading) Text("Downloading and installing platform-tools...", style = MaterialTheme.typography.bodySmall)
                consentNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (adbDownload == null) {
                Text("Browse, test and download arrive with the first-run screen (P4-7).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingSlider("Live interval", current.liveIntervalSeconds.toFloat(), 1f..10f, steps = 8, display = { "${it.toInt()} s" }) { value ->
                settings.update { it.copy(liveIntervalSeconds = value.toInt()) }
            }
        }
        SettingsSection("Snapshots") {
            SettingSwitch("Save every capture automatically", current.autoSaveSnapshots) { enabled ->
                settings.update { it.copy(autoSaveSnapshots = enabled) }
            }
            SettingSlider("Keep at most (snapshots)", current.retentionCount.toFloat(), 1f..500f, steps = 0, display = { "${it.toInt()}" }) { value ->
                settings.update { it.copy(retentionCount = value.toInt()) }
            }
            SettingSlider("Retention size cap (MB)", (current.retentionBytes / (1024.0 * 1024.0)).toFloat(), 50f..4096f, steps = 0, display = { "${it.toInt()} MB" }) { value ->
                settings.update { it.copy(retentionBytes = value.toInt().toLong() * 1024 * 1024) }
            }
            val usage = storageUsage.value
            if (usage != null) {
                // P5-3: the meter is against the configured byte cap; pinned snapshots are exempt from it (P1-7).
                val capBytes = current.retentionBytes.coerceAtLeast(1)
                val usedFraction = (usage.bytes.toDouble() / capBytes).coerceIn(0.0, 1.0)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceXs)) {
                    LinearProgressIndicator(
                        progress = { usedFraction.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${usage.count} snapshots - ${(usage.bytes + 512) / (1024 * 1024)} MB of ${current.retentionBytes / (1024 * 1024)} MB" +
                            if (usage.pinnedCount > 0) " (${usage.pinnedCount} pinned are exempt)" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text("Storage usage is unavailable.", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                OutlinedButton(onClick = onOpenSnapshots) { Text("Open folder") }
                OutlinedButton(onClick = { snapshots?.let { store -> ioScope.launch { store.clearAll() } } }) { Text("Clear all") }
            }
        }
        SettingsSection("Advanced") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                Text("Renderer", style = MaterialTheme.typography.bodyMedium)
                for (renderer in Renderer.entries) {
                    AssistChip(onClick = { settings.update { it.copy(renderer = renderer) } }, label = { Text(renderer.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }
            Text("The renderer change needs a restart.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var logLevelOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                Text("Log level", style = MaterialTheme.typography.bodyMedium)
                Box {
                    OutlinedButton(onClick = { logLevelOpen = true }) { Text(current.logLevel.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    DropdownMenu(expanded = logLevelOpen, onDismissRequest = { logLevelOpen = false }) {
                        for (level in LogLevelSetting.entries) {
                            DropdownMenuItem(text = { Text(level.name.lowercase().replaceFirstChar { it.uppercase() }) }, onClick = {
                                settings.update { it.copy(logLevel = level) }
                                logLevelOpen = false
                            })
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                Text("Default export format", style = MaterialTheme.typography.bodyMedium)
                for (format in ExportFormat.entries) {
                    AssistChip(onClick = { settings.update { it.copy(defaultExportFormat = format) } }, label = { Text(format.name) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                OutlinedButton(onClick = onOpenLogs) { Text("Open logs folder") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(diagnostics())) }) { Text("Copy diagnostics") }
                OutlinedButton(onClick = { confirmReset = true }) { Text("Reset all settings") }
            }
        }
        SettingsSection("About") {
            Text("${appInfo.name} ${appInfo.version}", style = MaterialTheme.typography.titleMedium)
            Text("Build ${appInfo.buildDate} (${appInfo.commit})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${appInfo.publisher} - ${appInfo.developer}", style = MaterialTheme.typography.bodySmall)
            Text("Licensed under the MIT licence.", style = MaterialTheme.typography.bodySmall)
            Text("Third-party notices are generated with the release build (P8-6).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                TextButton(onClick = { uriHandler.openUri(appInfo.docsUrl) }) { Text("Documentation") }
                TextButton(onClick = { uriHandler.openUri(appInfo.issuesUrl) }) { Text("Report an issue") }
                TextButton(onClick = { uriHandler.openUri(appInfo.privacyUrl) }) { Text("Privacy policy") }
                TextButton(onClick = { uriHandler.openUri(appInfo.repoUrl.trimEnd('/') + "/releases") }) { Text("What's new") }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all settings?") },
            text = { Text("Every setting goes back to its default. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    settings.reset()
                    confirmReset = false
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
    if (consentText != null) {
        com.titanicbhai.uiscope.ui.firstrun.AdbConsentDialog(
            consentText = consentText!!,
            onConfirm = {
                val actions = adbDownload ?: return@AdbConsentDialog
                consentText = null
                downloading = true
                ioScope.launch {
                    val result = actions.install { _, _ -> }
                    downloading = false
                    consentNote = when (result) {
                        is Outcome.Success -> "Platform-tools installed. Press Re-check."
                        is Outcome.Failure -> "The download failed (${result.error.code})."
                    }
                }
            },
            onDismiss = { consentText = null },
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(UiScopeTokens.spaceM), verticalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
    Spacer(Modifier.height(UiScopeTokens.spaceM))
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, display: (Float) -> String, onCommit: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(display(local), style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
            steps = steps,
        )
    }
}
