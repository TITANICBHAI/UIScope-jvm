package com.titanicbhai.uiscope.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.android.AdbDevice
import com.titanicbhai.uiscope.android.AdbDeviceState
import com.titanicbhai.uiscope.android.AdbManager
import com.titanicbhai.uiscope.android.ScreencapManager
import com.titanicbhai.uiscope.android.UiAutomatorParser
import com.titanicbhai.uiscope.codegen.CodegenPanel
import com.titanicbhai.uiscope.export.TreeExporter
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.InspectionMode
import com.titanicbhai.uiscope.model.Session
import com.titanicbhai.uiscope.repository.SessionRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

sealed class InspectorError {
    object AdbNotFound : InspectorError()
    object ScreenLocked : InspectorError()
    data class DumpFailed(val reason: String) : InspectorError()
}

enum class ExportFormat(val label: String, val ext: String) {
    JSON("JSON", "json"),
    XML("XML", "xml"),
    OUTLINE("Outline", "txt")
}

@Composable
fun InspectorScreen(
    mode: InspectionMode,
    adbManager: AdbManager,
    onSwitchMode: (InspectionMode) -> Unit,
    onBack: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val parser = remember { UiAutomatorParser() }
    val screencapMgr = remember { ScreencapManager(adbManager) }
    val sessionRepo = remember { SessionRepository() }

    // Device state
    var devices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }
    var isLoadingDevices by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<AdbDevice?>(null) }
    var showWirelessDialog by remember { mutableStateOf(false) }

    // Inspection state
    var elementTree by remember { mutableStateOf<List<ElementNode>>(emptyList()) }
    var rawScreenshotBytes by remember { mutableStateOf<ByteArray?>(null) }
    var screenshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var selectedNode by remember { mutableStateOf<ElementNode?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var inspectorError by remember { mutableStateOf<InspectorError?>(null) }
    var autoRefresh by remember { mutableStateOf(false) }
    val refreshIntervalMs = 2000L

    // UI toggles
    var showCodegen by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    val adbAvailable = remember { adbManager.isAdbAvailable() }

    suspend fun performRefresh(device: AdbDevice) {
        if (isRefreshing) return
        isRefreshing = true
        try {
            val xml = adbManager.dumpUiHierarchy(device.serial)
            when {
                xml.contains("error:", ignoreCase = true) && xml.contains("adb") ->
                    inspectorError = InspectorError.DumpFailed(xml.lines().firstOrNull()?.trim() ?: "Dump failed")
                xml.isBlank() || xml == "\n" ->
                    inspectorError = InspectorError.ScreenLocked
                else -> {
                    val parsed = parser.parse(xml)
                    if (parsed.isNotEmpty()) {
                        elementTree = parsed
                        inspectorError = null
                        // Capture screenshot
                        screencapMgr.capture(device.serial).onSuccess { bytes ->
                            rawScreenshotBytes = bytes
                            screenshot = runCatching {
                                loadImageBitmap(ByteArrayInputStream(bytes))
                            }.getOrNull()
                        }.onFailure {
                            if (inspectorError == null) {
                                inspectorError = InspectorError.ScreenLocked
                            }
                        }
                        // Persist session
                        try {
                            val sessionId = UUID.randomUUID().toString()
                            var screenshotPath: String? = null
                            rawScreenshotBytes?.let { b ->
                                val dir = File(System.getProperty("user.home"), ".uiscope/screenshots")
                                dir.mkdirs()
                                val f = File(dir, "$sessionId.png")
                                f.writeBytes(b)
                                screenshotPath = f.absolutePath
                            }
                            sessionRepo.insert(
                                Session(
                                    id = sessionId,
                                    timestamp = System.currentTimeMillis(),
                                    mode = InspectionMode.ANDROID,
                                    appName = parsed.firstOrNull()?.packageName,
                                    packageName = parsed.firstOrNull()?.packageName,
                                    deviceName = device.displayName,
                                    screenshotPath = screenshotPath,
                                    treeJson = TreeExporter.toJson(parsed)
                                )
                            )
                        } catch (_: Exception) {}
                    } else {
                        inspectorError = InspectorError.DumpFailed("Empty element tree — foreground app may not be accessible")
                    }
                }
            }
        } catch (e: Exception) {
            inspectorError = InspectorError.DumpFailed(e.message ?: "Unknown error")
        } finally {
            isRefreshing = false
        }
    }

    fun rescanDevices() {
        scope.launch {
            isLoadingDevices = true
            devices = adbManager.listDevices()
            isLoadingDevices = false
        }
    }

    // Initial device scan
    LaunchedEffect(mode) {
        if (mode == InspectionMode.ANDROID && adbAvailable) {
            isLoadingDevices = true
            devices = adbManager.listDevices()
            isLoadingDevices = false
        }
    }

    // Initial load when device selected
    LaunchedEffect(selectedDevice) {
        selectedDevice?.let { performRefresh(it) }
    }

    // Auto-refresh loop
    LaunchedEffect(selectedDevice, autoRefresh) {
        val device = selectedDevice ?: return@LaunchedEffect
        if (!autoRefresh) return@LaunchedEffect
        while (isActive) {
            delay(refreshIntervalMs)
            performRefresh(device)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colorScheme.background)) {
        InspectorTopBar(
            mode = mode,
            deviceName = selectedDevice?.displayName,
            isRefreshing = isRefreshing,
            autoRefresh = autoRefresh,
            showCodegen = showCodegen,
            onSwitchMode = onSwitchMode,
            onBack = onBack,
            onRefresh = { scope.launch { selectedDevice?.let { performRefresh(it) } } },
            onToggleAutoRefresh = { autoRefresh = !autoRefresh },
            onToggleCodegen = { showCodegen = !showCodegen },
            onExport = { showExportDialog = true }
        )

        HorizontalDivider(color = colorScheme.outline)

        if (isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), trackColor = colorScheme.surface)
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                !adbAvailable && mode == InspectionMode.ANDROID ->
                    AdbNotFoundScreen()

                mode == InspectionMode.ANDROID && selectedDevice == null ->
                    DeviceSelectionContent(
                        devices = devices,
                        isLoading = isLoadingDevices,
                        onDeviceSelected = {
                            selectedDevice = it
                            elementTree = emptyList()
                            screenshot = null
                            inspectorError = null
                        },
                        onRescan = ::rescanDevices,
                        onWireless = { showWirelessDialog = true }
                    )

                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(270.dp)
                                    .background(colorScheme.surface)
                            ) {
                                TreePanel(
                                    rootNodes = elementTree,
                                    selectedNode = selectedNode,
                                    onNodeSelected = { selectedNode = it }
                                )
                            }

                            VerticalDivider(color = colorScheme.outline)

                            Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                                Box(modifier = Modifier.weight(1f)) {
                                    if (inspectorError != null && elementTree.isEmpty()) {
                                        ErrorContent(
                                            error = inspectorError!!,
                                            onRetry = { scope.launch { selectedDevice?.let { performRefresh(it) } } },
                                            onBack = { selectedDevice = null }
                                        )
                                    } else {
                                        VisualCanvas(
                                            screenshot = screenshot,
                                            rootNodes = elementTree,
                                            selectedNode = selectedNode,
                                            onNodeSelected = { selectedNode = it },
                                            mode = mode
                                        )
                                    }
                                }
                                if (selectedNode != null) {
                                    HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.4f))
                                    BreadcrumbBar(
                                        rootNodes = elementTree,
                                        selectedNode = selectedNode,
                                        onNodeSelected = { selectedNode = it }
                                    )
                                }
                            }

                            VerticalDivider(color = colorScheme.outline)

                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(290.dp)
                                    .background(colorScheme.surface)
                            ) {
                                PropertiesPanel(node = selectedNode)
                            }
                        }

                        if (showCodegen) {
                            HorizontalDivider(color = colorScheme.outline)
                            CodegenPanel(selectedNode = selectedNode)
                        }
                    }
                }
            }
        }
    }

    if (showWirelessDialog) {
        WirelessAdbDialog(
            adbManager = adbManager,
            onDismiss = { showWirelessDialog = false },
            onConnected = {
                showWirelessDialog = false
                rescanDevices()
            }
        )
    }

    if (showExportDialog) {
        ExportDialog(
            elementTree = elementTree,
            selectedNode = selectedNode,
            onDismiss = { showExportDialog = false }
        )
    }
}

@Composable
private fun InspectorTopBar(
    mode: InspectionMode,
    deviceName: String?,
    isRefreshing: Boolean,
    autoRefresh: Boolean,
    showCodegen: Boolean,
    onSwitchMode: (InspectionMode) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleAutoRefresh: () -> Unit,
    onToggleCodegen: () -> Unit,
    onExport: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
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
                text = if (mode == InspectionMode.PC) "🖥  PC Inspector" else "📱  Android Inspector",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onSurface
            )
            deviceName?.let {
                Text("·", color = colorScheme.outline)
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRefresh, enabled = !isRefreshing) {
                Text("↺ Refresh", style = MaterialTheme.typography.labelMedium,
                    color = if (isRefreshing) colorScheme.onSurface.copy(alpha = 0.38f) else colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onToggleAutoRefresh) {
                Text(
                    if (autoRefresh) "⏸ Auto" else "▶ Auto",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (autoRefresh) colorScheme.primary else colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onToggleCodegen) {
                Text(
                    if (showCodegen) "Hide Code" else "{ } Code",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (showCodegen) colorScheme.primary else colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onExport) {
                Text("Export…", style = MaterialTheme.typography.labelMedium, color = colorScheme.onSurfaceVariant)
            }
            val otherMode = if (mode == InspectionMode.PC) InspectionMode.ANDROID else InspectionMode.PC
            OutlinedButton(
                onClick = { onSwitchMode(otherMode) },
                modifier = Modifier.height(34.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    if (mode == InspectionMode.PC) "Switch to Android" else "Switch to PC",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun DeviceSelectionContent(
    devices: List<AdbDevice>,
    isLoading: Boolean,
    onDeviceSelected: (AdbDevice) -> Unit,
    onRescan: () -> Unit,
    onWireless: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(500.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Connect a Device",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onBackground
            )

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Detected Devices", style = MaterialTheme.typography.labelLarge, color = colorScheme.onSurfaceVariant)
                        TextButton(onClick = onRescan, enabled = !isLoading) {
                            Text("↺ Rescan", style = MaterialTheme.typography.labelSmall, color = colorScheme.primary)
                        }
                    }

                    if (isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (devices.isEmpty()) {
                        NoDeviceHint()
                    } else {
                        devices.forEach { device ->
                            DeviceRow(device = device, onClick = { onDeviceSelected(device) })
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onWireless) {
                    Text("Pair Android 11+ via Code")
                }
                OutlinedButton(onClick = onWireless) {
                    Text("Manual IP Connect")
                }
            }

            Text(
                "💡 Make sure USB Debugging is enabled in Developer options",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun NoDeviceHint() {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⚠", color = colorScheme.error)
            Text("No devices detected", style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurface)
        }
        listOf(
            "USB cable connected?",
            "USB Debugging enabled? (Settings → Developer options)",
            "Trust dialog accepted on phone?",
            "Try unplugging and reconnecting the cable"
        ).forEach { hint ->
            Text(
                "  • $hint",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DeviceRow(device: AdbDevice, onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (device.state.isUsable) colorScheme.primaryContainer.copy(alpha = 0.25f)
                else colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            )
            .clickable(enabled = device.state.isUsable, onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                device.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onSurface
            )
            Text(
                "${device.serial}  ·  ${device.state.label}",
                style = MaterialTheme.typography.bodySmall,
                color = if (device.state.isUsable) colorScheme.primary else colorScheme.onSurfaceVariant
            )
        }
        if (device.state.isUsable) {
            Button(onClick = onClick) { Text("Inspect", style = MaterialTheme.typography.labelMedium) }
        } else if (device.state == AdbDeviceState.UNAUTHORIZED) {
            Text("Tap Allow on device →", style = MaterialTheme.typography.bodySmall, color = colorScheme.error)
        }
    }
}

@Composable
private fun AdbNotFoundScreen() {
    val colorScheme = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("🔌", style = MaterialTheme.typography.displaySmall)
            Text(
                "ADB not found",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onBackground
            )
            Text(
                "UIScope can't find adb. Make sure Android Platform Tools are installed and adb is in your PATH.",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
            Surface(color = colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quick fix:", style = MaterialTheme.typography.labelLarge, color = colorScheme.onSurface)
                    Text("macOS / Linux:", style = MaterialTheme.typography.labelMedium, color = colorScheme.onSurfaceVariant)
                    Text("  brew install android-platform-tools", style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.primary)
                    Text("Windows:", style = MaterialTheme.typography.labelMedium, color = colorScheme.onSurfaceVariant)
                    Text("  Download from developer.android.com/tools/releases/platform-tools",
                        style = MaterialTheme.typography.bodySmall, color = colorScheme.primary)
                    Text("Then restart UIScope.", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(
    error: InspectorError,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (error) {
                is InspectorError.ScreenLocked -> {
                    Text("🔒", style = MaterialTheme.typography.displaySmall)
                    Text("Screen locked or inaccessible",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    Text("Unlock the device and tap Retry.",
                        style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurfaceVariant)
                }
                is InspectorError.DumpFailed -> {
                    Text("⚠", style = MaterialTheme.typography.displaySmall)
                    Text("UI dump failed",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    Text(error.reason.take(200),
                        style = MaterialTheme.typography.bodySmall, color = colorScheme.error)
                    Text("The app may not support accessibility, or the screen changed mid-dump.",
                        style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurfaceVariant)
                }
                else -> {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) { Text("↺ Retry") }
                OutlinedButton(onClick = onBack) { Text("← Choose device") }
            }
        }
    }
}

@Composable
private fun ExportDialog(
    elementTree: List<ElementNode>,
    selectedNode: ElementNode?,
    onDismiss: () -> Unit
) {
    var format by remember { mutableStateOf(ExportFormat.JSON) }
    var exportSelected by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Tree", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Format", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.entries.forEach { f ->
                        FilterChip(
                            selected = format == f,
                            onClick = { format = f },
                            label = { Text(f.label) }
                        )
                    }
                }
                if (selectedNode != null) {
                    Text("Scope", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !exportSelected, onClick = { exportSelected = false }, label = { Text("Full tree") })
                        FilterChip(selected = exportSelected, onClick = { exportSelected = true }, label = { Text("Selected node") })
                    }
                }
                statusMessage?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val node = if (exportSelected) selectedNode else null
                    val content = when (format) {
                        ExportFormat.JSON -> TreeExporter.toJson(elementTree, node)
                        ExportFormat.XML -> TreeExporter.toXml(elementTree, node)
                        ExportFormat.OUTLINE -> TreeExporter.toOutline(elementTree, node)
                    }
                    try {
                        val chooser = JFileChooser()
                        chooser.selectedFile = java.io.File("uiscope-export.${format.ext}")
                        val filter = FileNameExtensionFilter("${format.label} files", format.ext)
                        chooser.fileFilter = filter
                        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                            var file = chooser.selectedFile
                            if (!file.name.endsWith(".${format.ext}")) {
                                file = java.io.File("${file.absolutePath}.${format.ext}")
                            }
                            file.writeText(content)
                            statusMessage = "✓ Saved to ${file.name}"
                        }
                    } catch (e: Exception) {
                        statusMessage = "Error: ${e.message}"
                    }
                },
                enabled = elementTree.isNotEmpty()
            ) {
                Text("Save…")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
