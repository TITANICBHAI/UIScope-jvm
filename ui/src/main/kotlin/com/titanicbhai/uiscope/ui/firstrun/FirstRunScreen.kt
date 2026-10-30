package com.titanicbhai.uiscope.ui.firstrun

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.settings.InspectTarget
import com.titanicbhai.uiscope.state.AndroidInspectUi
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens
import java.nio.file.Path
import kotlinx.coroutines.launch

/**
 * The ADB-download actions the shell wires to the engine's downloader (P2-4). The ui module only sees lambdas -
 * the consent text comes pre-rendered and the install reports its progress.
 */
data class AdbDownloadActions(
    /** Success carries the consent text (AdbDownloader.consent().text()); Failure explains why downloading is unavailable. */
    val consentText: () -> Outcome<String>,
    /** The confirmed download+install; progress in bytes. */
    val install: suspend (onProgress: (Long, Long?) -> Unit) -> Outcome<Path>,
)

data class FirstRunCallbacks(
    val onRecheck: () -> Unit = {},
    val onSetTarget: (InspectTarget) -> Unit = {},
    val onSetCheckUpdates: (Boolean) -> Unit = {},
    val onBrowseAdb: () -> Unit = {},
    /** The user finished or skipped; the shell records firstRunDone and opens Inspect. */
    val onFinish: () -> Unit = {},
)

/**
 * The first-run screen (plan P4-7, 4.2): three steps, all skippable, none blocking. The quick checks show **real**
 * results - the adb status comes from the state holder, never a hard-coded tick.
 */
@Composable
fun FirstRunScreen(
    ui: AndroidInspectUi,
    target: InspectTarget,
    windowsPcAvailable: Boolean,
    storeBuild: Boolean,
    checkForUpdates: Boolean,
    download: AdbDownloadActions,
    callbacks: FirstRunCallbacks,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf(0) }
    var consentText by remember { mutableStateOf<String?>(null) }
    var consentUnavailable by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadNote by remember { mutableStateOf<String?>(null) }
    var selfInstall by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiScopeTokens.spaceXl)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Welcome to UIScope", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { callbacks.onFinish() }) { Text("Skip") }
        }
        when (step) {
            0 -> {
                Text(
                    "UIScope shows every element of an Android screen or a Windows app window and tells you which " +
                        "selector you can trust. Pick or capture, select an element, read the verdict, copy the code.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(UiScopeTokens.spaceL))
                Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceXl), verticalAlignment = Alignment.CenterVertically) {
                    StepIcon(Icons.Filled.Search, "Capture")
                    StepIcon(Icons.Filled.TaskAlt, "Verdict")
                    StepIcon(Icons.Filled.ContentCopy, "Copy code")
                }
            }
            1 -> {
                Text("What do you want to inspect?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UiScopeTokens.spaceM))
                Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceM)) {
                    TargetCard("Android device", selected = target == InspectTarget.ANDROID, onClick = { callbacks.onSetTarget(InspectTarget.ANDROID) })
                    if (windowsPcAvailable) {
                        TargetCard("Windows app", selected = target == InspectTarget.WINDOWS, onClick = { callbacks.onSetTarget(InspectTarget.WINDOWS) })
                    }
                }
            }
            else -> {
                Text("Quick checks", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UiScopeTokens.spaceM))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(UiScopeTokens.spaceM), verticalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                        Text("Android", style = MaterialTheme.typography.titleSmall)
                        val adb = ui.adb
                        if (adb != null) {
                            Text("ADB found: ${adb.path} (version ${adb.version}, via ${adb.source})", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("ADB not found", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!selfInstall) {
                            Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                                Button(onClick = {
                                    when (val consent = download.consentText()) {
                                        is Outcome.Success -> {
                                            consentUnavailable = null
                                            consentText = consent.value
                                        }
                                        is Outcome.Failure -> consentUnavailable = "Downloading is not available right now (${consent.error.code}). Install adb yourself and use Browse."
                                    }
                                }) { Text("Download platform-tools") }
                                OutlinedButton(onClick = { callbacks.onBrowseAdb() }) { Text("Browse...") }
                                OutlinedButton(onClick = { selfInstall = true }) { Text("I'll install it myself") }
                            }
                            if (consentUnavailable != null) Text(consentUnavailable!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (downloading) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                                CircularProgressIndicator(Modifier.size(20.dp))
                                Text("Downloading and installing platform-tools...")
                            }
                        }
                        downloadNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = { callbacks.onRecheck() }) { Text("Re-check") }
                    }
                }
                if (windowsPcAvailable) {
                    Spacer(Modifier.height(UiScopeTokens.spaceM))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(UiScopeTokens.spaceM), verticalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                            Text("Windows", style = MaterialTheme.typography.titleSmall)
                            Text("UI Automation helper: arrives with the Windows engine (Phase 3).", style = MaterialTheme.typography.bodySmall)
                            Text("Elevated windows need UIScope to run as administrator.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(UiScopeTokens.spaceM))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(value = checkForUpdates, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = { callbacks.onSetCheckUpdates(it) }),
                ) {
                    Switch(checked = checkForUpdates, onCheckedChange = null)
                    Text("Check for updates on startup", modifier = Modifier.padding(start = UiScopeTokens.spaceS))
                }
            }
        }
        Spacer(Modifier.height(UiScopeTokens.spaceL))
        Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
            if (step > 0) OutlinedButton(onClick = { step-- }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { if (step == 2) callbacks.onFinish() else step++ }) { Text(if (step == 2) "Finish" else "Next") }
        }
    }
    consentText?.let { text ->
        AdbConsentDialog(
            consentText = text,
            onConfirm = {
                consentText = null
                downloading = true
                scope.launch {
                    val result = download.install { _, _ -> }
                    downloading = false
                    downloadNote = when (result) {
                        is Outcome.Success -> "Platform-tools installed. Re-checking..."
                        is Outcome.Failure -> "The download failed (${result.error.code}). You can retry or use Browse."
                    }
                    if (result is Outcome.Success) callbacks.onRecheck()
                }
            },
            onDismiss = { consentText = null },
        )
    }
}

@Composable
private fun StepIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TargetCard(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(4.dp),
    ) {
        Box(Modifier.clickable(onClick = onClick).padding(UiScopeTokens.spaceXl), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The ADB-download consent dialog (plan P4-7); the text comes from the engine's [com.titanicbhai.uiscope.adb.AdbDownloader]. */
@Composable
fun AdbConsentDialog(consentText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download platform-tools?") },
        text = { Text(consentText) },
        confirmButton = { Button(onClick = onConfirm) { Text("Download and install") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
