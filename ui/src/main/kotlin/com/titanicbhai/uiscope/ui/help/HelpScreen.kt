package com.titanicbhai.uiscope.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import com.titanicbhai.uiscope.AppInfo
import com.titanicbhai.uiscope.changelog.ChangelogEntry
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

/** Where the website keeps its pages; the app only links to them when the project has a website (app.properties). */
internal fun websitePage(appInfo: AppInfo, page: String): String = appInfo.websiteUrl.trimEnd('/') + "/" + page

/**
 * Help and About (plan P4-7, extended): who and what this is with the version, every link a user may need in plain sight (website,
 * support, documentation, troubleshooting, privacy, source), the "Copy diagnostics" button that makes a support request answerable,
 * what's new in this version, the Welcome replay and the shortcuts sheet.
 */
@Composable
fun HelpScreen(
    appInfo: AppInfo,
    onReplayFirstRun: () -> Unit,
    releaseNotes: List<ChangelogEntry> = emptyList(),
    diagnostics: () -> String = { "" },
    onMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val hasSite = appInfo.websiteUrl.isNotBlank()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiScopeTokens.spaceXl),
        verticalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceM),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = UiScopeTokens.cornerMedium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(UiScopeTokens.spaceL), verticalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                Text("${appInfo.name} ${appInfo.version}", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Build ${appInfo.buildDate} (${appInfo.commit}) - ${appInfo.developer}, ${appInfo.publisher}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "UIScope shows every element of an Android screen or a Windows app window and tells you which selector " +
                        "you can trust. Pick or capture, select an element, read the verdict, copy the code.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                    if (hasSite) Button(onClick = { uriHandler.openUri(appInfo.websiteUrl) }) { Text("Website") }
                    Button(onClick = { uriHandler.openUri(appInfo.supportUrl) }) { Text("Get support") }
                    OutlinedButton(onClick = { uriHandler.openUri(appInfo.issuesUrl) }) { Text("Report an issue") }
                    OutlinedButton(onClick = { uriHandler.openUri(appInfo.repoUrl) }) { Text("Source code") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceXs)) {
                    TextButton(onClick = { uriHandler.openUri(appInfo.docsUrl) }) { Text("Documentation") }
                    if (hasSite) {
                        TextButton(onClick = { uriHandler.openUri(websitePage(appInfo, "docs.html#troubleshooting")) }) { Text("Troubleshooting") }
                        TextButton(onClick = { uriHandler.openUri(websitePage(appInfo, "changelog.html")) }) { Text("Changelog online") }
                    }
                    TextButton(onClick = { uriHandler.openUri(appInfo.privacyUrl) }) { Text("Privacy policy") }
                }
            }
        }

        Text("Something not working?", style = MaterialTheme.typography.titleMedium)
        Text(
            "Press Copy diagnostics, then open Get support or Report an issue and paste it in. It lists the UIScope version, your system and " +
                "what UIScope sees of your phone or window, and never contains anything from your screens.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
            Button(onClick = {
                clipboard.setText(AnnotatedString(diagnostics()))
                onMessage("Diagnostics copied. Paste them into your support request.")
            }) { Text("Copy diagnostics") }
            OutlinedButton(onClick = onReplayFirstRun) { Text("Run the welcome again") }
        }

        releaseNotes.firstOrNull()?.let { latest ->
            Text("What's new", style = MaterialTheme.typography.titleMedium)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = UiScopeTokens.cornerMedium, modifier = Modifier.fillMaxWidth()) {
                ReleaseNotesView(latest, Modifier.padding(UiScopeTokens.spaceM))
            }
        }

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
    }
}
