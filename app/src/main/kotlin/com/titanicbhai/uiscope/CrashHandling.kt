package com.titanicbhai.uiscope

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Crash handling (plan P7-2): the default uncaught-exception handler logs with full context and shows a Swing dialog
 * (the Compose loop may be dead at that point) with *Copy diagnostics* and *Restart*. Coroutine failures never kill
 * the app, so they are only logged. The helper-crash half of the task (restart as `UIA_UNAVAILABLE`) is the
 * WindowsHelperClient's own restart policy from P3-2 and is tested there.
 *
 * [showDialog] is injectable so the test can drive the exact handler without a display.
 */
object CrashHandling {
    private val logger = Logger.getLogger("uiscope.crash")

    fun install(
        diagnostics: () -> String,
        relaunch: () -> Unit,
        showDialog: (title: String, text: String, actions: List<Pair<String, () -> Unit>>) -> Unit = ::swingDialog,
    ) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logger.log(Level.SEVERE, "uncaught exception on thread " + thread.name, throwable)
            val text = buildString {
                append(diagnostics())
                append("\n\n")
                append(throwable.toString())
                append("\n")
                append(throwable.stackTrace.joinToString("\n") { "  at $it" })
            }
            try {
                showDialog(
                    "UIScope hit an unexpected problem",
                    text.take(4000),
                    listOf("Copy diagnostics" to { copyToClipboard(text) }, "Restart" to relaunch, "Close" to {}),
                )
            } catch (dialogFailure: Throwable) {
                logger.log(Level.SEVERE, "the crash dialog itself failed", dialogFailure)
            }
        }
    }

    /** Coroutine failures are logged with their context; the dialog stays for the fatal thread-level crashes. */
    fun coroutineHandler(): CoroutineExceptionHandler = CoroutineExceptionHandler { context, throwable ->
        logger.log(Level.SEVERE, "uncaught coroutine failure in $context", throwable)
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    private fun swingDialog(title: String, text: String, actions: List<Pair<String, () -> Unit>>) {
        SwingUtilities.invokeLater {
            val labels = actions.map { it.first }.toTypedArray()
            val chosen = JOptionPane.showOptionDialog(null, text, title, JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, labels, labels.first())
            if (chosen >= 0) actions[chosen].second()
        }
    }
}
