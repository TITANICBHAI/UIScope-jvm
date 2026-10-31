package com.titanicbhai.uiscope.ui.shell

import com.titanicbhai.uiscope.testing.awaitTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** P7-1 (M15): the lock is exclusive, the second launch exits after leaving the focus marker, and the first window's callback fires. */
class LockFileSingleInstanceTest {

    private fun dir(): Path = Files.createTempDirectory("uiscope-single-instance")

    @Test
    fun `the second launch writes the focus marker and exits instead of starting`() {
        val d = dir()
        val first = LockFileSingleInstance(d.resolve("a.lock"), d.resolve("a.focus"), pollMillis = 10)
        assertTrue(first.tryAcquire(), "the first instance takes the lock")
        var focused = 0
        var exited = 0
        val second = LockFileSingleInstance(
            d.resolve("a.lock"),
            d.resolve("a.focus"),
            exit = { exited++ },
            pollMillis = 10,
        )
        second.start(onSecondLaunch = { focused++ })
        assertEquals(1, exited, "the second instance exits itself")
        assertEquals(0, focused, "the second instance never focuses itself")
        assertTrue(Files.exists(d.resolve("a.focus")), "the focus request waits for the first instance")
        first.release()
        second.release()
    }

    @Test
    fun `the first instance consumes the focus marker and calls back`() = runBlocking {
        val d = dir()
        var callbacks = 0
        val first = LockFileSingleInstance(d.resolve("b.lock"), d.resolve("b.focus"), pollMillis = 10)
        first.start(onSecondLaunch = { callbacks++ })
        Files.writeString(d.resolve("b.focus"), "now")
        withTimeout(5000) { awaitTrue(5000, "the watcher fires the focus callback") { callbacks == 1 } }
        assertFalse(Files.exists(d.resolve("b.focus")), "the marker is consumed")
        first.release()
        // After release the lock is free again.
        val third = LockFileSingleInstance(d.resolve("b.lock"), d.resolve("b.focus"), pollMillis = 10)
        assertTrue(third.tryAcquire(), "the lock is reusable after release")
        third.release()
    }
}
