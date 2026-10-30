package com.titanicbhai.uiscope

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.logging.LogSafe
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.snapshot.SnapshotMeta
import com.titanicbhai.uiscope.state.AndroidInspectState
import com.titanicbhai.uiscope.state.AndroidState
import com.titanicbhai.uiscope.storage.DataPaths
import com.titanicbhai.uiscope.storage.SnapshotStoreImpl
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import com.titanicbhai.uiscope.update.UpdateCheck
import com.titanicbhai.uiscope.update.UpdateChecker
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TEMPORARY developer harness (plan P2-5) until the real UI exists in Phase 4: lets the owner check Android capture on a real phone.
 * It shows what adb was found, the devices, the state, and for the latest capture the element count, the warnings and the screenshot,
 * which can be saved into `<data>/dev-captures` or into the snapshot store (the early manual check for storage, MANUAL_QA Round 5).
 * Nothing here is user-facing design.
 */
@Composable
fun DevHarness(state: AndroidInspectState, info: AppInfo, paths: DataPaths, snapshots: MutableState<SnapshotStoreImpl?>, updater: UpdateChecker, mode: ThemeMode) {
    val ui by state.ui.collectAsState()
    var saved by remember { mutableStateOf<String?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val capture = ui.capture
    val store = snapshots.value
    UiScopeTheme(mode) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${info.name} ${info.version}: developer harness (temporary, Android capture check)")
            Text("adb: " + (ui.adb?.let { "${it.path}  (version ${it.version}, found via ${it.source})" } ?: "not found"))
            Text("Snapshots: " + (store?.let { "ready" } ?: "opening…"))
            ui.problem?.let { Text("Problem: ${it.code}: ${it.detail.orEmpty()}") }
            Text("State: ${describe(ui.state)}")
            if (ui.devices.isEmpty()) Text("Devices: none") else ui.devices.forEach { Text("Device …${LogSafe.serial(it.serial)}: ${it.model ?: "unknown model"}, ${it.state}, ${it.connection}") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { state.capture() }) { Text("Capture now") }
                Button(onClick = { state.start() }) { Text("Look for adb and devices again") }
                Button(onClick = { scope.launch { updateStatus = withContext(Dispatchers.IO) { updater.check() }.let { describeUpdate(it) } } }) {
                    Text("Check for updates")
                }
            }
            updateStatus?.let { Text(it) }
            if (capture != null) {
                Text("Elements: ${ui.index?.size ?: 0}   Windows: ${capture.roots.size}   Warnings: ${capture.warnings.joinToString { it.code }.ifEmpty { "none" }}")
                Text("Screenshot: ${capture.screenshot?.let { "${it.width} x ${it.height} px, ${it.file}" } ?: "none"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { saved = withContext(Dispatchers.IO) { saveScreenshot(capture, paths) } } }, enabled = capture.screenshot != null) {
                        Text("Save screenshot")
                    }
                    if (store != null) {
                        // save() does its file and database work on the I/O dispatcher internally; the result is a state update.
                        Button(onClick = { scope.launch { saved = describeSave(store.save(capture)) } }) { Text("Save as snapshot") }
                    }
                }
            }
            saved?.let { Text(it) }
        }
    }
}

private suspend fun describeSave(result: Outcome<SnapshotMeta>): String = when (result) {
    is Outcome.Success -> "Saved snapshot ${result.value.id}"
    is Outcome.Failure -> "Could not save the snapshot: ${result.error.code}"
}

internal fun describeUpdate(result: UpdateCheck): String = when (result) {
    UpdateCheck.Disabled -> "Update check: disabled (setting off or Store build)"
    UpdateCheck.UpToDate -> "Update check: up to date"
    is UpdateCheck.Available -> "Update available: v${result.version}"
    is UpdateCheck.Failed -> "Update check failed: ${result.error.code}"
}

internal fun describe(state: AndroidState): String = when (state) {
    AndroidState.Starting -> "looking for adb…"
    AndroidState.AdbMissing -> "adb not found"
    AndroidState.NoDevice -> "no device connected"
    is AndroidState.ChooseDevice -> "several devices: ${state.devices.size}"
    is AndroidState.Unauthorized -> "device not authorised: accept the prompt on the phone"
    is AndroidState.Offline -> "device offline"
    is AndroidState.NoPermissions -> "no permission to use the device (udev rules)"
    is AndroidState.Ready -> "ready"
    is AndroidState.Capturing -> "capturing…"
    is AndroidState.Captured -> "captured"
    is AndroidState.CaptureFailed -> "capture failed: ${state.error.code}"
    is AndroidState.Disconnected -> "disconnected"
}

/** Copies the capture's screenshot to `<data>/dev-captures/<serial tail>-<time>.png` and returns a sentence for the screen. */
internal fun saveScreenshot(capture: Capture, paths: DataPaths): String {
    val shot = capture.screenshot ?: return "No screenshot to save"
    return try {
        val dir = paths.root.resolve("dev-captures")
        Files.createDirectories(dir)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val target: Path = dir.resolve("capture-$stamp.png")
        Files.copy(Path.of(shot.file), target)
        "Saved $target"
    } catch (e: IOException) {
        "Could not save the screenshot: ${e.javaClass.simpleName}"
    }
}
