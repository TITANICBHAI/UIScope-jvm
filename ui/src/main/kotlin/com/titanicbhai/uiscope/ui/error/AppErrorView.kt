package com.titanicbhai.uiscope.ui.error

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.ui.Strings
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens

/** Where an error is shown: a slim row above content (M11: errors are visible *beside* content, never instead of it), or a full screen when there is nothing beside it. */
enum class ErrorVariant { BANNER, FULL_SCREEN }

/**
 * The one error component (plan P4-10, §4.10): title · what happened · primary action · *Copy details*, copy from
 * [Strings] by code — the UI never invents error text. [onAction] receives the error when the primary action is clicked;
 * [onDismiss], when given, adds a close affordance.
 */
@Composable
fun AppErrorView(
    error: AppError,
    variant: ErrorVariant,
    modifier: Modifier = Modifier,
    onAction: (AppError) -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    val copy = Strings.error(error.code)
    val clipboard = LocalClipboardManager.current
    val details = buildString {
        append("code: ").appendLine(error.code)
        error.detail?.let { append("detail: ").appendLine(it) }
        error.remedy?.let { append("remedy: ").appendLine(it.name) }
    }.trimEnd()

    when (variant) {
        ErrorVariant.BANNER -> Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = UiScopeTokens.cornerMedium,
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = UiScopeTokens.spaceM, vertical = UiScopeTokens.spaceS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(UiScopeTokens.spaceM),
            ) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, modifier = Modifier.size(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(copy.title, style = MaterialTheme.typography.titleSmall)
                    Text(copy.what, style = MaterialTheme.typography.bodySmall)
                }
                copy.action?.let { label -> TextButton(onClick = { onAction(error) }) { Text(label) } }
                TextButton(onClick = { clipboard.setText(AnnotatedString(details)) }) { Text(Strings.ACTION_COPY_DETAILS) }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = Strings.ACTION_DISMISS) }
                }
            }
        }
        ErrorVariant.FULL_SCREEN -> Column(
            modifier = modifier.fillMaxWidth().padding(UiScopeTokens.spaceXl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(UiScopeTokens.spaceXxl))
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(UiScopeTokens.spaceM))
            Text(copy.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(UiScopeTokens.spaceS))
            Text(
                copy.what,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(UiScopeTokens.spaceL))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(UiScopeTokens.spaceM)) {
                copy.action?.let { label -> Button(onClick = { onAction(error) }) { Text(label) } }
                TextButton(onClick = { clipboard.setText(AnnotatedString(details)) }) { Text(Strings.ACTION_COPY_DETAILS) }
            }
        }
    }
}
