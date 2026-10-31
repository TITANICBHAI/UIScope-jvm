package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.export.CaptureJson
import com.titanicbhai.uiscope.export.OutlineExporter
import com.titanicbhai.uiscope.export.XmlExporter
import com.titanicbhai.uiscope.export.redacted
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.settings.ExportFormat
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One export format of the dialog (4.8); [ExportFormat] (Settings) has no PNG because it only defaults text exports. */
enum class ExportDialogFormat(val label: String, val extension: String) {
    JSON("JSON", "json"),
    XML("XML", "xml"),
    OUTLINE("Outline", "outline.txt"),
    PNG("PNG", "png"),
}

fun exportDialogFormat(default: ExportFormat): ExportDialogFormat = when (default) {
    ExportFormat.JSON -> ExportDialogFormat.JSON
    ExportFormat.XML -> ExportDialogFormat.XML
    ExportFormat.OUTLINE -> ExportDialogFormat.OUTLINE
}

/** The shell side of the dialog: a native Save dialog plus the PNG rendering (image decoding and outlining stay in the app). */
data class ExportActions(
    val saveAs: suspend (suggestedName: String, content: ByteArray) -> Outcome<Unit> =
        { _, _ -> Outcome.Failure(CodedError(ErrorCodes.UNEXPECTED, "saving is unavailable")) },
    val renderPng: suspend (capture: Capture, highlight: ElementPath?) -> ByteArray? = { _, _ -> null },
)

/**
 * The export dialog (plan P5-2, 4.8): scope (whole capture / selected subtree), format (JSON / XML / Outline / PNG),
 * redaction, and Copy to clipboard (text formats) or Save as... (native dialog, written off the UI thread). Text
 * generation runs on Dispatchers.Default so copying a 5k-node export never blocks the UI.
 */
@Composable
fun ExportDialog(
    capture: Capture,
    index: ElementIndex?,
    selected: ElementPath?,
    defaultFormat: ExportFormat,
    appVersion: String,
    actions: ExportActions,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit = {},
) {
    var wholeCapture by remember { mutableStateOf(true) }
    var format by remember { mutableStateOf(exportDialogFormat(defaultFormat)) }
    var redact by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS), verticalAlignment = Alignment.CenterVertically) {
                    Text("Scope", style = MaterialTheme.typography.labelLarge)
                    AssistChip(onClick = { wholeCapture = true }, label = { Text("Whole capture") })
                    AssistChip(
                        onClick = { wholeCapture = false },
                        enabled = selected != null,
                        label = { Text(if (selected != null) "Selected subtree" else "Selected subtree (select one first)") },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS), verticalAlignment = Alignment.CenterVertically) {
                    Text("Format", style = MaterialTheme.typography.labelLarge)
                    for (f in ExportDialogFormat.entries) {
                        AssistChip(onClick = { format = f }, label = { Text(f.label) })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(value = redact, role = Role.Switch, onValueChange = { redact = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Redact text values", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = UiScopeTokens.spaceS))
                    Switch(checked = redact, onCheckedChange = null)
                }
                if (redact && format == ExportDialogFormat.PNG) {
                    Text(
                        "Screenshots cannot be redacted - the PNG keeps the original text.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    suggestedName(capture, format),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                OutlinedButton(
                    enabled = !busy && format != ExportDialogFormat.PNG,
                    onClick = {
                        busy = true
                        scope.launch {
                            val bytes = withContext(Dispatchers.Default) {
                                exportContent(capture, index, selected, wholeCapture, format, redact, appVersion)
                            }
                            busy = false
                            if (bytes == null) {
                                onMessage("Nothing to copy.")
                            } else {
                                clipboard.setText(AnnotatedString(bytes.toString(Charsets.UTF_8)))
                                onMessage("Copied the ${format.label} export to the clipboard.")
                            }
                        }
                    },
                ) { Text("Copy to clipboard") }
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val bytes = if (format == ExportDialogFormat.PNG) {
                                actions.renderPng(capture, if (wholeCapture) null else selected)
                            } else {
                                withContext(Dispatchers.Default) {
                                    exportContent(capture, index, selected, wholeCapture, format, redact, appVersion)
                                }
                            }
                            val result = bytes?.let { actions.saveAs(suggestedName(capture, format), it) }
                            busy = false
                            when (result) {
                                is Outcome.Success -> onDismiss
                                is Outcome.Failure -> onMessage("The export could not be saved (${result.error.code}).")
                                null -> onMessage("The PNG could not be rendered.")
                            }
                        }
                    },
                ) { Text("Save as...") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

internal fun suggestedName(capture: Capture, format: ExportDialogFormat): String =
    "uiscope-" + capture.id.filter { it.isLetterOrDigit() || it == '-' }.take(40) + "." + format.extension

/**
 * The pure text-export pipeline (the PNG goes through [ExportActions.renderPng]): redaction first, then the subtree
 * scope (the selected node becomes the only root, its own subtree intact), then the format. Internal so the sentinel
 * test drives exactly what the dialog copies.
 */
internal fun exportContent(
    capture: Capture,
    index: ElementIndex?,
    selected: ElementPath?,
    wholeCapture: Boolean,
    format: ExportDialogFormat,
    redact: Boolean,
    appVersion: String,
): ByteArray? {
    if (format == ExportDialogFormat.PNG) return null
    var effective = if (redact) capture.redacted() else capture
    if (!wholeCapture && selected != null && index != null) {
        index.byPath[selected]?.let { node -> effective = effective.copy(roots = listOf(node)) }
    }
    val text = when (format) {
        ExportDialogFormat.JSON -> CaptureJson.encode(effective, appVersion, includeSerial = false)
        ExportDialogFormat.XML -> XmlExporter.export(effective)
        ExportDialogFormat.OUTLINE -> OutlineExporter.export(effective)
        ExportDialogFormat.PNG -> return null
    }
    return text.toByteArray(Charsets.UTF_8)
}
