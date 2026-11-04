package com.titanicbhai.uiscope.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.changelog.ChangelogEntry
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens

/** The notes of one release as they appear in the app: a heading, a few friendly lines, then New / Fixed / Improved with their bullets. */
@Composable
fun ReleaseNotesView(entry: ChangelogEntry, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceXs)) {
        Text(
            "Version ${entry.version}" + (entry.date?.let { " - $it" } ?: ""),
            style = MaterialTheme.typography.titleMedium,
        )
        if (entry.intro.isNotBlank()) Text(entry.intro, style = MaterialTheme.typography.bodyMedium)
        for (section in entry.shownSections) {
            Text(section.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = UiScopeTokens.spaceS))
            for (item in section.items) {
                Row {
                    Text("\u2022", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(14.dp))
                    Text(item, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Shown once after an update, and whenever the version button in the status bar is pressed. */
@Composable
fun WhatsNewDialog(entry: ChangelogEntry, onDismiss: () -> Unit, onOpenHelp: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's new in UIScope ${entry.version}") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { ReleaseNotesView(entry) } },
        confirmButton = { Button(onClick = onDismiss) { Text("Got it") } },
        dismissButton = {
            if (onOpenHelp != null) {
                TextButton(onClick = {
                    onDismiss()
                    onOpenHelp()
                }) { Text("Help and support") }
            }
        },
    )
}
