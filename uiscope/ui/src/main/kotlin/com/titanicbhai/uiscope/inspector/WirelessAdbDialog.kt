package com.titanicbhai.uiscope.inspector

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.android.AdbManager
import kotlinx.coroutines.launch

@Composable
fun WirelessAdbDialog(
    adbManager: AdbManager,
    onDismiss: () -> Unit,
    onConnected: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }

    var ipPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Connect Wirelessly", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0; resultMessage = null }) {
                        Text("Pre-Android 11", modifier = Modifier.padding(vertical = 10.dp),
                            style = MaterialTheme.typography.labelMedium)
                    }
                    Tab(selected = tab == 1, onClick = { tab = 1; resultMessage = null }) {
                        Text("Android 11+ (Pair)", modifier = Modifier.padding(vertical = 10.dp),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }

                when (tab) {
                    0 -> PreAndroid11Tab(
                        ipPort = ipPort,
                        onIpPortChange = { ipPort = it; resultMessage = null }
                    )
                    1 -> Android11PairTab(
                        ipPort = ipPort,
                        pairingCode = pairingCode,
                        onIpPortChange = { ipPort = it; resultMessage = null },
                        onCodeChange = { pairingCode = it; resultMessage = null }
                    )
                }

                resultMessage?.let { msg ->
                    Surface(
                        color = if (isError) colorScheme.errorContainer else colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            msg,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isError) colorScheme.onErrorContainer else colorScheme.onPrimaryContainer
                        )
                    }
                }

                if (isConnecting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(
                enabled = ipPort.isNotBlank() && !isConnecting &&
                        (tab == 0 || pairingCode.isNotBlank()),
                onClick = {
                    scope.launch {
                        isConnecting = true
                        resultMessage = null
                        try {
                            val ok = when (tab) {
                                0 -> adbManager.connectDevice(ipPort.trim())
                                else -> adbManager.pairDevice(ipPort.trim(), pairingCode.trim())
                            }
                            if (ok) {
                                resultMessage = if (tab == 0)
                                    "✓ Connected! The device should now appear in the list."
                                else
                                    "✓ Paired! Now connect via the Pre-Android 11 tab or rescan."
                                isError = false
                                onConnected()
                            } else {
                                resultMessage = "Could not connect. Check IP, port, and that wireless debugging is enabled."
                                isError = true
                            }
                        } catch (e: Exception) {
                            resultMessage = "Error: ${e.message}"
                            isError = true
                        } finally {
                            isConnecting = false
                        }
                    }
                }
            ) {
                Text(if (tab == 0) "Connect" else "Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PreAndroid11Tab(ipPort: String, onIpPortChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Enable TCP/IP mode on the device (run once via USB):",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                "adb tcpip 5555",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            "Then unplug USB and enter the device IP address:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = ipPort,
            onValueChange = onIpPortChange,
            label = { Text("IP:Port — e.g. 192.168.1.42:5555") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun Android11PairTab(
    ipPort: String,
    pairingCode: String,
    onIpPortChange: (String) -> Unit,
    onCodeChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "On your Android 11+ device:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        listOf(
            "1. Go to Settings → Developer options → Wireless debugging",
            "2. Tap \"Pair device with pairing code\"",
            "3. Note the IP:Port and 6-digit code shown on screen"
        ).forEach { step ->
            Text(step, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        OutlinedTextField(
            value = ipPort,
            onValueChange = onIpPortChange,
            label = { Text("Pairing IP:Port — from the device screen") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pairingCode,
            onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) onCodeChange(it) },
            label = { Text("6-digit pairing code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
