package com.titanicbhai.uiscope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P7-2: the handler logs, builds the diagnostics text and hands the dialog its Copy/Restart actions. */
class CrashHandlingTest {

    @Test
    fun `an uncaught exception reaches the dialog with diagnostics and both actions`() {
        val dialogCalls = ArrayList<Triple<String, String, List<String>>>()
        var restarted = 0
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            CrashHandling.install(
                diagnostics = { "APP 1.0 test\nOS: TestOS" },
                relaunch = { restarted++ },
                showDialog = { title, text, actions -> dialogCalls += Triple(title, text, actions.map { it.first }) },
            )
            val handler = Thread.getDefaultUncaughtExceptionHandler() ?: throw AssertionError("the handler is installed")
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom-marker"))
            assertEquals(1, dialogCalls.size, "exactly one dialog")
            val (title, text, actions) = dialogCalls.first()
            assertTrue(text.contains("APP 1.0 test"), "the diagnostics are in the dialog")
            assertTrue(text.contains("boom-marker"), "the exception is in the dialog")
            assertEquals(listOf("Copy diagnostics", "Restart", "Close"), actions)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `a failing dialog never throws out of the handler`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            CrashHandling.install(
                diagnostics = { "diag" },
                relaunch = {},
                showDialog = { _, _, _ -> throw IllegalStateException("headless") },
            )
            val handler = Thread.getDefaultUncaughtExceptionHandler()!!
            // The real handler must swallow a dialog failure, not crash the crash path.
            handler.uncaughtException(Thread.currentThread(), RuntimeException("first"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
