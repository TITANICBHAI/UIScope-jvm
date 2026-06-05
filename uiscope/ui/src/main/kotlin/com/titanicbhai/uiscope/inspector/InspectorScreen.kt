@file:OptIn(ExperimentalComposeUiApi::class)

package com.titanicbhai.uiscope.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.titanicbhai.uiscope.android.AdbDevice
import com.titanicbhai.uiscope.android.AdbDeviceState
import com.titanicbhai.uiscope.android.AdbManager
import com.titanicbhai.uiscope.android.ScreencapManager
import com.titanicbhai.uiscope.android.UiAutomatorParser
import com.titanicbhai.uiscope.codegen.CodegenPanel
import com.titanicbhai.uiscope.export.TreeExporter
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.InspectionMode
import com.titanicbhai.uiscope.model.Session
import com.titanicbhai.uiscope.pc.OsKind
import com.titanicbhai.uiscope.pc.PcInspector
import com.titanicbhai.uiscope.pc.PcInspectorFactory
import com.titanicbhai.uiscope.pc.PermissionInstructions
import com.titanicbhai.uiscope.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.MouseInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
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

    val pcInspector = remember { PcInspectorFactory.create() }
    val osKind = remember { PcInspectorFactory.currentOs }

    DisposableEffect(Unit) { onDispose { pcInspector.dispose() } }

    // Android state
    var devices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }
    var isLoadingDevices by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<AdbDevice?>(null) }
    var showWirelessDialog by remember { mutableStateOf(false) }

    // Shared inspection state
    var elementTree by remember { mutableStateOf<List<ElementNode>>(emptyList()) }
    var rawScreenshotBytes by remember { mutableStateOf<ByteArray?>(null) }
    var screenshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var selectedNode by remember { mutableStateOf<ElementNode?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var inspectorError by remember { mutableStateOf<InspectorError?>(null) }
    var autoRefresh by remember { mutableStateOf(false) }
    val refreshIntervalMs = 2000L

    // PC-specific state
    var pcPermissionGranted by remember { mutableStateOf(true) }
    var pcPermissionInstructions by remember { mutableStateOf<PermissionInstructions?>(null) }
    var pickModeActive by remember { mutableStateOf(false) }
    var pcWindowHandle by remember { mutableStateOf(0L) }
    var pcLockedElement by remember { mutableStateOf<ElementNode?>(null) }

    // UI toggles
    var showCodegen by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    val adbAvailable = remember { adbManager.isAdbAvailable() }

    // Check PC permissions on entry to PC mode
    LaunchedEffect(mode) {
        if (mode == InspectionMode.PC) {
            withContext(Dispatchers.IO) {
                pcPermissionGranted = pcInspector.isPermissionGranted()
                if (!pcPermissionGranted) {
                    pcPermissionInstructions = pcInspector.getPermissionInstructions()
                }
            }
        }
    }

    // Set up PC event subscription for tree updates
    LaunchedEffect(mode, pcLockedElement) {
        if (mode == InspectionMode.PC && pcLockedElement != null) {
            pcInspector.startEventSubscription {
                scope.launch {
                    val handle = pcWindowHandle
                    if (handle != 0L) {
                        val newTree = withContext(Dispatchers.IO) {
                            pcInspector.getRootTree(handle)
                        }
                        if (newTree.isNotEmpty()) elementTree = newTree
                    }
                }
            }
        } else {
            pcInspector.stopEventSubscription()
        }
    }

    suspend fun performAndroidRefresh(device: AdbDevice) {
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
                        screencapMgr.capture(device.serial).onSuccess { bytes ->
                            rawScreenshotBytes = bytes
                            screenshot = runCatching { loadImageBitmap(ByteArrayInputStream(bytes)) }.getOrNull()
                        }.onFailure {
                            if (inspectorError == null) inspectorError = InspectorError.ScreenLocked
                        }
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

    suspend fun lockPcElement(screenX: Int, screenY: Int) {
        isRefreshing = true
        try {
            val handle = withContext(Dispatchers.IO) {
                pcInspector.getWindowHandleAt(screenX, screenY)
            }
            pcWindowHandle = handle
            val tree = withContext(Dispatchers.IO) { pcInspector.getRootTree(handle) }
            val locked = withContext(Dispatchers.IO) { pcInspector.findElementAtPoint(screenX, screenY) }
            val bytes = withContext(Dispatchers.IO) { pcInspector.captureWindowScreenshot(handle) }
            elementTree = tree
            pcLockedElement = locked ?: tree.firstOrNull()
            selectedNode = locked
            rawScreenshotBytes = bytes
            screenshot = bytes?.let { runCatching { loadImageBitmap(ByteArrayInputStream(it)) }.getOrNull() }
            // Persist session
            try {
                val sessionId = UUID.randomUUID().toString()
                var screenshotPath: String? = null
                bytes?.let { b ->
                    val dir = File(System.getProperty("user.home"), ".uiscope/screenshots")
                    dir.mkdirs()
                    val f = File(dir, "$sessionId.png")
                    f.writeBytes(b)
                    screenshotPath = f.absolutePath
                }
                val winInfo = withContext(Dispatchers.IO) { pcInspector.getWindowInfo(handle) }
                sessionRepo.insert(
                    Session(
                        id = sessionId,
                        timestamp = System.currentTimeMillis(),
                        mode = InspectionMode.PC,
                        appName = winInfo?.title,
                        packageName = null,
                        deviceName = osKind.name,
                        screenshotPath = screenshotPath,
                        treeJson = TreeExporter.toJson(tree)
                    )
                )
            } catch (_: Exception) {}
        } catch (e: Exception) {
            inspectorError = InspectorError.DumpFailed(e.message ?: "PC lock failed")
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

    LaunchedEffect(mode) {
        if (mode == InspectionMode.ANDROID && adbAvailable) {
            isLoadingDevices = true
            devices = adbManager.listDevices()
            isLoadingDevices = false
        }
        // Reset state on mode switch
        elementTree = emptyList()
        screenshot = null
        selectedNode = null
        inspectorError = null
        pcLockedElement = null
        pickModeActive = false
    }

    LaunchedEffect(selectedDevice) {
        selectedDevice?.let { performAndroidRefresh(it) }
    }

    LaunchedEffect(selectedDevice, autoRefresh) {
        val device = selectedDevice ?: return@LaunchedEffect
        if (!autoRefresh) return@LaunchedEffect
        while (isActive) {
            delay(refreshIntervalMs)
            performAndroidRefresh(device)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .onKeyEvent { event ->
                if (mode == InspectionMode.PC &&
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.P &&
                    event.isAltPressed &&
                    event.isShiftPressed
                ) {
                    pickModeActive = !pickModeActive
                    true
                } else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    pickModeActive = false
                    true
                } else if (mode == InspectionMode.ANDROID && event.type == KeyEventType.KeyDown && event.key == Key.R) {
                    scope.launch { selectedDevice?.let { performAndroidRefresh(it) } }
                    true
                } else false
            }
    ) {
        InspectorTopBar(
            mode = mode,
            deviceName = if (mode == InspectionMode.PC) pcInspector.getWindowInfo(pcWindowHandle)?.title?.let {
                if (it.isNotBlank()) it else null
            } else selectedDevice?.displayName,
            isRefreshing = isRefreshing,
            autoRefresh = autoRefresh,
            showCodegen = showCodegen,
            pickModeActive = pickModeActive,
            onSwitchMode = onSwitchMode,
            onBack = onBack,
            onRefresh = {
                if (mode == InspectionMode.PC) {
                    scope.launch {
                        if (pcWindowHandle != 0L) {
                            isRefreshing = true
                            val tree = withContext(Dispatchers.IO) { pcInspector.getRootTree(pcWindowHandle) }
                            if (tree.isNotEmpty()) elementTree = tree
                            isRefreshing = false
                        }
                    }
                } else {
                    scope.launch { selectedDevice?.let { performAndroidRefresh(it) } }
                }
            },
            onToggleAutoRefresh = { autoRefresh = !autoRefresh },
            onToggleCodegen = { showCodegen = !showCodegen },
            onExport = { showExportDialog = true },
            onTogglePick = { pickModeActive = !pickModeActive }
        )

        HorizontalDivider(color = colorScheme.outline)

        if (isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), trackColor = colorScheme.surface)
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                !adbAvailable && mode == InspectionMode.ANDROID ->
                    AdbNotFoundScreen()

                mode == InspectionMode.PC && !pcPermissionGranted ->
                    PcPermissionScreen(
                        instructions = pcPermissionInstructions,
                        onRecheck = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    pcPermissionGranted = pcInspector.isPermissionGranted()
                                }
                            }
                        }
                    )

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

                mode == InspectionMode.PC && pcLockedElement == null ->
                    PcPickPrompt(
                        osKind = osKind,
                        onPickClick = { pickModeActive = true }
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
                                            onRetry = {
                                                if (mode == InspectionMode.PC) {
                                                    pcLockedElement = null
                                                    pickModeActive = false
                                                } else {
                                                    scope.launch { selectedDevice?.let { performAndroidRefresh(it) } }
                                                }
                                            },
                                            onBack = {
                                                if (mode == InspectionMode.PC) {
                                                    pcLockedElement = null; elementTree = emptyList(); screenshot = null
                                                } else {
                                                    selectedDevice = null
                                                }
                                            }
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
                                PropertiesPanel(node = selectedNode, mode = mode)
                            }
                        }

                        if (showCodegen) {
                            HorizontalDivider(color = colorScheme.outline)
                            CodegenPanel(selectedNode = selectedNode, mode = mode)
                        }
                    }
                }
            }
        }
    }

    // PC pick-mode overlay window
    if (pickModeActive && mode == InspectionMode.PC) {
        PickModeOverlay(
            onElementPicked = { screenX, screenY ->
                pickModeActive = false
                scope.launch { lockPcElement(screenX, screenY) }
            },
            onCancel = { pickModeActive = false }
        )
    }

    if (showWirelessDialog) {
        WirelessAdbDialog(
            adbManager = adbManager,
            onDismiss = { showWirelessDialog = false },
            onConnected = { showWirelessDialog = false; rescanDevices() }
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
private fun PickModeOverlay(
    onElementPicked: (Int, Int) -> Unit,
    onCancel: () -> Unit
) {
    var mouseX by remember { mutableStateOf(0) }
    var mouseY by remember { mutableStateOf(0) }
    var hoverLabel by remember { mutableStateOf("Move mouse to an element…") }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val loc = MouseInfo.getPointerInfo()?.location
                if (loc != null) {
                    mouseX = loc.x
                    mouseY = loc.y
                    hoverLabel = "Click to lock on element at ($mouseX, $mouseY)"
                }
            } catch (_: Exception) {}
            delay(50)
        }
    }

    Window(
        onCloseRequest = onCancel,
        transparent = true,
        undecorated = true,
        state = WindowState(placement = WindowPlacement.Fullscreen),
        title = "UIScope Pick Mode",
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onCancel()
                true
            } else false
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .onPointerEvent(PointerEventType.Press) { event ->
                    val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                    onElementPicked(mouseX, mouseY)
                }
        ) {
            val crossSize = 40f
            val cx = mouseX.toFloat()
            val cy = mouseY.toFloat()

            Box(
                modifier = Modifier
                    .offset(x = (mouseX - 200).dp.coerceAtLeast(0.dp), y = (mouseY + 20).dp)
                    .background(
                        Color(0xFF1A1A2E).copy(alpha = 0.95f),
                        shape = MaterialTheme.shapes.small
                    )
                    .border(1.dp, Color(0xFF1A6EC7).copy(alpha = 0.8f), MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    Text(
                        "🎯 Pick Mode",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF1A6EC7)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hoverLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Text(
                        "Click to lock · Esc to cancel",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopStart
            ) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.padding(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A6EC7).copy(alpha = 0.85f)
                    )
                ) {
                    Text("✕  Cancel Pick Mode (Esc)", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun PcPickPrompt(
    osKind: OsKind,
    onPickClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text("🖥️", style = MaterialTheme.typography.displayMedium)
            Text(
                "PC Inspector",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onBackground
            )
            Text(
                "Hover over any element on this PC and click to lock on it.",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onPickClick,
                modifier = Modifier.height(44.dp)
            ) {
                Text("🎯  Pick Element  (Alt+Shift+P)")
            }

            Surface(
                color = colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        when (osKind) {
                            OsKind.WINDOWS -> "Windows — Using UIAutomation"
                            OsKind.MACOS -> "macOS — Using Accessibility API"
                            OsKind.LINUX -> "Linux — Using AT-SPI2 / xdotool"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Activate pick mode, move over any window element, then click to inspect its full tree and properties.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PcPermissionScreen(
    instructions: PermissionInstructions?,
    onRecheck: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("🔐", style = MaterialTheme.typography.displaySmall)
            Text(
                instructions?.title ?: "Permission required",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onBackground
            )

            if (instructions != null) {
                Surface(color = colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        instructions.steps.forEach { step ->
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("•", color = colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                Text(step, style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurface)
                            }
                        }
                    }
                }

                val actionLabel = instructions.actionLabel
                val actionUrl = instructions.actionUrl
                if (actionLabel != null && actionUrl != null) {
                    OutlinedButton(onClick = {
                        try {
                            Desktop.getDesktop().browse(URI(actionUrl))
                        } catch (_: Exception) {}
                    }) {
                        Text(actionLabel)
                    }
                }
            }

            Button(onClick = onRecheck) {
                Text("↺ Re-check Permission")
            }
        }
    }
}

@Composable
private fun InspectorTopBar(
    mode: InspectionMode,
    deviceName: String?,
    isRefreshing: Boolean,
    autoRefresh: Boolean,
    showCodegen: Boolean,
    pickModeActive: Boolean,
    onSwitchMode: (InspectionMode) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleAutoRefresh: () -> Unit,
    onToggleCodegen: () -> Unit,
    onExport: () -> Unit,
    onTogglePick: () -> Unit
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
                Text(it, style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (mode == InspectionMode.PC) {
                Button(
                    onClick = onTogglePick,
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (pickModeActive) Color(0xFF1A6EC7) else colorScheme.primaryContainer,
                        contentColor = if (pickModeActive) Color.White else colorScheme.onPrimaryContainer
                    )
                ) {
                    Text(
                        if (pickModeActive) "⏹ Cancel Pick" else "🎯 Pick",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            TextButton(onClick = onRefresh, enabled = !isRefreshing) {
                Text(
                    "↺ Refresh",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isRefreshing) colorScheme.onSurface.copy(alpha = 0.38f) else colorScheme.onSurfaceVariant
                )
            }
            if (mode == InspectionMode.ANDROID) {
                TextButton(onClick = onToggleAutoRefresh) {
                    Text(
                        if (autoRefresh) "⏸ Auto" else "▶ Auto",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (autoRefresh) colorScheme.primary else colorScheme.onSurfaceVariant
                    )
                }
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
                OutlinedButton(onClick = onWireless) { Text("Pair Android 11+ via Code") }
                OutlinedButton(onClick = onWireless) { Text("Manual IP Connect") }
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
            Text("  • $hint", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
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
                    Text("  brew install android-platform-tools", style = MaterialTheme.typography.bodySmall, color = colorScheme.primary)
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
                    Text("Inspection failed",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    Text(error.reason.take(200),
                        style = MaterialTheme.typography.bodySmall, color = colorScheme.error)
                    Text("The app may not support accessibility, or the element changed.",
                        style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurfaceVariant)
                }
                else -> {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) { Text("↺ Retry") }
                OutlinedButton(onClick = onBack) { Text("← Back") }
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
                        FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
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
            Button(onClick = {
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
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
