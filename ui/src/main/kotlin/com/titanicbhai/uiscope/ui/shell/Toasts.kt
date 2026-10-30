package com.titanicbhai.uiscope.ui.shell

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

/**
 * Toasts (plan §4.1): copy confirmations and cues like "Snapshot saved". Informational toasts auto-dismiss after 3 s;
 * error toasts stay until the user dismisses them. One toast at a time — the newest replaces the previous.
 */
class ToastState internal constructor() {
    data class Toast(val id: Long, val message: String, val isError: Boolean)

    var current: Toast? by mutableStateOf(null)
        private set

    private val counter = AtomicLong()

    fun show(message: String) {
        current = Toast(counter.incrementAndGet(), message, isError = false)
    }

    fun showError(message: String) {
        current = Toast(counter.incrementAndGet(), message, isError = true)
    }

    fun dismiss() {
        current = null
    }
}

private const val AUTO_DISMISS_MILLIS = 3_000L

@Composable
fun rememberToastState(): ToastState = ToastState()

@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    val toast = state.current ?: return
    LaunchedEffect(toast.id) {
        if (!toast.isError) {
            delay(AUTO_DISMISS_MILLIS)
            state.dismiss()
        }
    }
    Surface(
        modifier = modifier,
        shape = UiScopeTokens.cornerMedium,
        color = if (toast.isError) androidx.compose.material3.MaterialTheme.colorScheme.errorContainer else androidx.compose.material3.MaterialTheme.colorScheme.inverseSurface,
        contentColor = if (toast.isError) androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer else androidx.compose.material3.MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Row(modifier = Modifier.padding(horizontal = UiScopeTokens.spaceM, vertical = UiScopeTokens.spaceS), verticalAlignment = Alignment.CenterVertically) {
            Text(toast.message, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            if (toast.isError) {
                IconButton(onClick = state::dismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
            }
        }
    }
}
