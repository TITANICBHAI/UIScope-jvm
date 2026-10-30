package com.titanicbhai.uiscope.ui.error

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.titanicbhai.uiscope.error.AppError

/** Where an error is shown: a slim row above content, or a full screen when there is nothing to show beside it. */
enum class ErrorVariant { BANNER, FULL_SCREEN }

/**
 * RED STUB (plan P4-10, regression M11): renders nothing, exactly like the old UI that showed `ErrorContent` only when the
 * tree was empty — an error beside content was invisible. The M11 test fails on its assertions against this; the real
 * component replaces the body.
 */
@Composable
fun AppErrorView(
    error: AppError,
    variant: ErrorVariant,
    modifier: Modifier = Modifier,
    onAction: (AppError) -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    // Stub: draws nothing.
}
