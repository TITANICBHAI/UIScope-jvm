package com.titanicbhai.uiscope.testing

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Builds command lines for [FakeProcessMain] and inspects the processes it starts. */
object FakeProcess {
    private val javaBin: String =
        File(System.getProperty("java.home"), if (isWindows()) "bin/java.exe" else "bin/java").path

    // Works whether the fixtures are loaded from a directory or a jar, and under Gradle or a plain classpath.
    private val classpath: String = listOf(FakeProcess::class.java, Unit::class.java)
        .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
        .distinct()
        .joinToString(File.pathSeparator)

    fun command(vararg args: String): List<String> =
        listOf(javaBin, "-cp", classpath, "com.titanicbhai.uiscope.testing.FakeProcessMain") + args

    /** True when the process no longer runs. A zombie that nobody reaped counts as dead (containers without an init). */
    fun isDead(pid: Long): Boolean {
        val handle = ProcessHandle.of(pid)
        if (handle.isEmpty || !handle.get().isAlive) return true
        val stat = File("/proc/$pid/stat")
        if (stat.exists()) {
            val state = stat.readText().substringAfterLast(')').trim().firstOrNull()
            return state == 'Z'
        }
        return false
    }

    /** Polls until every pid is dead or [timeoutMs] passes. */
    fun awaitDead(pids: List<Long>, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (pids.all(::isDead)) return true
            Thread.sleep(50)
        }
        return pids.all(::isDead)
    }

    /** Waits for the "self,child" pid file written by the `spawn-tree` mode. */
    fun awaitPids(pidFile: Path, timeoutMs: Long = 15_000): List<Long> {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                val text = Files.readString(pidFile).trim()
                if (text.contains(',')) return text.split(',').map { it.toLong() }
            }
            Thread.sleep(50)
        }
        error("spawn-tree did not write its pid file within $timeoutMs ms")
    }

    private fun isWindows() = System.getProperty("os.name").orEmpty().lowercase().contains("win")
}
