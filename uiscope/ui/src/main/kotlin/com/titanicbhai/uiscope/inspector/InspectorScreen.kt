package com.titanicbhai.uiscope.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.android.AdbDevice
import com.titanicbhai.uiscope.android.AdbManager
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.InspectionMode
import kotlinx.coroutines.launch

@Composable
fun InspectorScreen(
    mode: InspectionMode,
    adbManager: AdbManager,
    onSwitchMode: (InspectionMode) -> Unit,
    onBack: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var selectedNode by remember { mutableStateOf<ElementNode?>(null) }
    var devices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }
    var isLoadingDevices by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(mode) {
        if (mode == InspectionMode.ANDROID) {
            isLoadingDevices = true
            devices = adbManager.listDevices()
            isLoadingDevices = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colorScheme.background)) {
        InspectorTopBar(
            mode = mode,
            onSwitchMode = onSwitchMode,
            onBack = onBack,
            onRefresh = {
                scope.launch {
                    if (mode == InspectionMode.ANDROID) {
                        isLoadingDevices = true
                        devices = adbManager.listDevices()
                        isLoadingDevices = false
                    }
                }
            }
        )

        HorizontalDivider(color = colorScheme.outline)

        if (mode == InspectionMode.ANDROID && devices.isEmpty() && !isLoadingDevices) {
            AndroidDeviceSelectionPanel(
                devices = devices,
                isLoading = isLoadingDevices,
                adbManager = adbManager,
                onDeviceSelected = { /* Phase 2 */ }
            )
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.fillMaxHeight().width(280.dp).background(colorScheme.surface)
                ) {
                    TreePanel(
                        nodes = emptyList(),
                        selectedNode = selectedNode,
                        onNodeSelected = { selectedNode = it }
                    )
                }

                VerticalDivider(color = colorScheme.outline)

                Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
                    VisualCanvas(
                        screenshot = null,
                        selectedNode = selectedNode,
                        mode = mode
                    )
                }

                VerticalDivider(color = colorScheme.outline)

                Box(
                    modifier = Modifier.fillMaxHeight().width(300.dp).background(colorScheme.surface)
                ) {
                    PropertiesPanel(node = selectedNode)
                }
            }
        }
    }
}

@Composable
private fun InspectorTopBar(
    mode: InspectionMode,
    onSwitchMode: (InspectionMode) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val otherMode = if (mode == InspectionMode.PC) InspectionMode.ANDROID else InspectionMode.PC
    val otherLabel = if (mode == InspectionMode.PC) "Switch to Android" else "Switch to PC"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(colorScheme.surface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) {
                Text("← Back", color = colorScheme.primary)
            }
            Text(
                text = if (mode == InspectionMode.PC) "🖥️  PC Inspector" else "📱  Android Inspector",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onSurface
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefresh) {
                Text("↺ Refresh", color = colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { onSwitchMode(otherMode) }) {
                Text(otherLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun AndroidDeviceSelectionPanel(
    devices: List<AdbDevice>,
    isLoading: Boolean,
    adbManager: AdbManager,
    onDeviceSelected: (AdbDevice) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var manualAddress by remember { mutableStateOf("") }
    var connectError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(480.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Connect a Device",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onBackground
            )

            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "USB / Wireless ADB",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSurfaceVariant
                    )

                    if (isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (devices.isEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚠", color = colorScheme.error)
                            Column {
                                Text(
                                    "No devices detected",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurface
                                )
                                Text(
                                    "Check: USB cable connected? USB Debugging on? Trust dialog accepted on phone?",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        devices.forEach { device ->
                            DeviceRow(device = device, onClick = { onDeviceSelected(device) })
                        }
                    }
                }
            }

            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Manual Wireless ADB",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = manualAddress,
                            onValueChange = { manualAddress = it; connectError = null },
                            label = { Text("192.168.x.x:5555") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            isError = connectError != null
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    val ok = adbManager.connectDevice(manualAddress)
                                    if (!ok) connectError = "Could not connect. Check IP and port."
                                }
                            },
                            enabled = manualAddress.isNotBlank()
                        ) {
                            Text("Connect")
                        }
                    }
                    connectError?.let {
                        Text(it, color = colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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
                if (device.state.isUsable) colorScheme.primaryContainer.copy(alpha = 0.3f)
                else colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            )
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
                device.state.label,
                style = MaterialTheme.typography.bodySmall,
                color = if (device.state.isUsable) colorScheme.primary else colorScheme.onSurfaceVariant
            )
        }
        if (device.state.isUsable) {
            Button(onClick = onClick) { Text("Inspect") }
        }
    }
}
