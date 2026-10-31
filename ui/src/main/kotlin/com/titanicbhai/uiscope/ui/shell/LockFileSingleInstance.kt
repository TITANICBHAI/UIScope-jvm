package com.titanicbhai.uiscope.ui.shell

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The single-instance guard (plan P7-1, regression M15): a `FileLock` on `<data>/single-instance.lock`. The first
 * launch holds the lock for the process lifetime and watches the focus file; a second launch cannot take the lock,
 * writes the focus marker and exits, and the first window comes to the front. All of it is plain NIO, so it behaves
 * identically on Windows and Linux and is testable without a display.
 */
class LockFileSingleInstance(
    private val lockFile: Path,
    private val focusFile: Path,
    /** How the second instance exits; injected so tests can observe instead of killing the JVM. */
    private val exit: () -> Unit = { exitProcess(0) },
    private val pollMillis: Long = 1000,
) : SingleInstanceHook {

    private val logger = Logger.getLogger("uiscope.shell")
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    @Volatile
    private var watching = false

    override fun start(onSecondLaunch: () -> Unit) {
        if (tryAcquire()) {
            watch(onSecondLaunch)
        } else {
            // A second UIScope: ask the first one to come forward, then leave (M15).
            Files.createDirectories(focusFile.parent)
            Files.writeString(focusFile, Instant.now().toString())
            exit()
        }
    }

    /** Takes the lock; false when another live instance already holds it. */
    fun tryAcquire(): Boolean {
        Files.createDirectories(lockFile.parent)
        val opened = FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
        val taken = opened.tryLock()
        if (taken == null) {
            opened.close()
            return false
        }
        channel = opened
        lock = taken
        return true
    }

    private fun watch(onSecondLaunch: () -> Unit) {
        watching = true
        thread(isDaemon = true, name = "uiscope-single-instance") {
            while (watching) {
                try {
                    if (Files.exists(focusFile)) {
                        Files.deleteIfExists(focusFile)
                        onSecondLaunch()
                    }
                } catch (t: Throwable) {
                    logger.warning("the single-instance watcher failed once: " + t.javaClass.simpleName)
                }
                try {
                    Thread.sleep(pollMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@thread
                }
            }
        }
    }

    /** Stops the watcher and drops the lock (called from the shell's exit path). */
    fun release() {
        watching = false
        try {
            lock?.release()
        } catch (t: Throwable) {
            logger.warning("releasing the instance lock failed: " + t.javaClass.simpleName)
        }
        try {
            channel?.close()
        } catch (t: Throwable) {
            logger.warning("closing the instance lock file failed: " + t.javaClass.simpleName)
        }
    }
}
