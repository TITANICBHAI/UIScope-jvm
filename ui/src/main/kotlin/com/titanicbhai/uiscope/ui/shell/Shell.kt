package com.titanicbhai.uiscope.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens

/** The rail destinations (plan §4.1). Screens arrive with P4-3 (Inspect), P5-1 (Snapshots) and P4-8 (Settings). */
enum class ShellScreen { INSPECT, SNAPSHOTS, SETTINGS, HELP }

/**
 * Hook point for P7-1 (single instance, regression M15): the shell starts nothing itself; P7-1 replaces [NoSingleInstance]
 * with the lock-file guard that focuses the first window when a second launch arrives.
 */
fun interface SingleInstanceHook {
    fun start(onSecondLaunch: () -> Unit)
}

object NoSingleInstance : SingleInstanceHook {
    override fun start(onSecondLaunch: () -> Unit) = Unit
}

/** What the status bar shows (plan §4.1): target on the left, capture info in the middle, warning and Live on the right. */
data class ShellStatus(val target: String, val captureInfo: String? = null, val warning: AppError? = null, val live: Boolean = false)

/** A modal question the shell shows through [ShellDialogState] (Enter = primary, Esc = cancel, plan §4.1). */
data class ShellDialog(val title: String, val text: String, val confirmLabel: String, val onConfirm: () -> Unit)

class ShellDialogState internal constructor() {
    var current: ShellDialog? by mutableStateOf(null)
        private set

    fun show(dialog: ShellDialog) {
        current = dialog
    }

    fun dismiss() {
        current = null
    }
}

@Composable
fun rememberShellDialogState(): ShellDialogState = ShellDialogState()

/**
 * The app shell (plan P4-2, §4.1): one window, a left navigation rail, a 24 dp status bar, toast and dialog hosts.
 * The menu bar is rendered by the caller inside the Window scope (it is a FrameWindowScope extension); the shell owns
 * nothing about the active screen, passed as [content].
 */
@Composable
fun AppShell(
    screen: ShellScreen,
    onScreen: (ShellScreen) -> Unit,
    status: ShellStatus,
    toasts: ToastState,
    dialogs: ShellDialogState,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
            NavigationRail {
                NavigationRailItem(
                    selected = screen == ShellScreen.INSPECT,
                    onClick = { onScreen(ShellScreen.INSPECT) },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("Inspect") },
                )
                NavigationRailItem(
                    selected = screen == ShellScreen.SNAPSHOTS,
                    onClick = { onScreen(ShellScreen.SNAPSHOTS) },
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                    label = { Text("Snapshots") },
                )
                NavigationRailItem(
                    selected = screen == ShellScreen.SETTINGS,
                    onClick = { onScreen(ShellScreen.SETTINGS) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
                Spacer(Modifier.weight(1f))
                NavigationRailItem(
                    selected = screen == ShellScreen.HELP,
                    onClick = { onScreen(ShellScreen.HELP) },
                    icon = { Icon(Icons.Filled.HelpOutline, contentDescription = null) },
                    label = { Text("Help") },
                )
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                content()
                ToastHost(toasts, Modifier.align(Alignment.BottomCenter).padding(UiScopeTokens.spaceM))
            }
        }
        StatusBar(status, onWarningClick = {
            status.warning?.let { w -> dialogs.show(ShellDialog(w.code, w.detail ?: "", "Close", onConfirm = {})) }
        })
        dialogs.current?.let { dialog -> ShellDialogHost(dialog, dialogs::dismiss) }
    }
}

/** The 24 dp status bar (plan §4.1): target · capture info · warning chip · Live indicator. */
@Composable
private fun StatusBar(status: ShellStatus, onWarningClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().height(24.dp).padding(horizontal = UiScopeTokens.spaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(status.target, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            status.captureInfo?.let {
                Text("  ·  $it", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            if (status.warning != null) {
                TextButton(onClick = onWarningClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = UiScopeTokens.spaceS, vertical = 0.dp)) {
                    Text(status.warning.code, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (status.live) {
                Icon(Icons.Filled.Lens, contentDescription = null, modifier = Modifier.height(10.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Live", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = UiScopeTokens.spaceXs))
            }
        }
    }
}

/** Modal dialog host: Enter confirms, Esc cancels (plan §4.1). */
@Composable
fun ShellDialogHost(dialog: ShellDialog, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialog.title) },
        text = {
            Text(
                dialog.text,
                modifier = Modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                        dialog.onConfirm()
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            )
        },
        confirmButton = { TextButton(onClick = { dialog.onConfirm(); onDismiss() }) { Text(dialog.confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
