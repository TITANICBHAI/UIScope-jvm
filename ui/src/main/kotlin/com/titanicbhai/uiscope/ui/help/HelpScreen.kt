package com.titanicbhai.uiscope.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.AppInfo
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens

/** The keyboard shortcuts sheet (plan 4.11): no single-letter shortcuts. */
private val SHORTCUTS = listOf(
    "Capture / Refresh" to "F5, Ctrl+R",
    "Toggle Live" to "Ctrl+L",
    "Pick element (global, Windows, optional)" to "Ctrl+Alt+P",
    "Focus search" to "Ctrl+F",
    "Next / previous match" to "Enter / Shift+Enter",
    "Save snapshot" to "Ctrl+S",
    "Export" to "Ctrl+E",
    "Copy best selector" to "Ctrl+Shift+C",
    "Copy code" to "Ctrl+Shift+K",
    "Select parent" to "Alt+Up",
    "Cancel pick or capture / close dialog / clear search" to "Esc",
)

/**
 * The Help screen (plan P4-7): the Welcome text (re-opening the first run is one click away), the shortcuts sheet and
 * the documentation, issue and privacy links.
 */
@Composable
fun HelpScreen(
    appInfo: AppInfo,
    onReplayFirstRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiScopeTokens.spaceXl), verticalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceM)) {
        Text("Welcome to UIScope", style = MaterialTheme.typography.headlineSmall)
        Text(
            "UIScope shows every element of an Android screen or a Windows app window and tells you which selector " +
                "you can trust. Pick or capture, select an element, read the verdict, copy the code.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onReplayFirstRun) { Text("Run the welcome again") }
        Text("Keyboard shortcuts", style = MaterialTheme.typography.titleMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(UiScopeTokens.spaceM)) {
                for ((action, shortcut) in SHORTCUTS) {
                    Row(Modifier.fillMaxWidth().padding(vertical = UiScopeTokens.spaceXs)) {
                        Text(action, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(shortcut, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
            TextButton(onClick = { uriHandler.openUri(appInfo.docsUrl) }) { Text("Documentation") }
            TextButton(onClick = { uriHandler.openUri(appInfo.issuesUrl) }) { Text("Report an issue") }
            TextButton(onClick = { uriHandler.openUri(appInfo.privacyUrl) }) { Text("Privacy policy") }
        }
    }
}
