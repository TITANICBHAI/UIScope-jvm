@file:JvmName("FakeProcessMain")

package com.titanicbhai.uiscope.testing

import java.io.File
import kotlin.system.exitProcess

/**
 * A tiny fake executable for process tests. It is a JVM `main`, so it behaves identically on Windows and Linux.
 * Started through [FakeProcess.command]; modes:
 *
 *  exit <code>                          exit with <code>
 *  stdout-and-stderr <out> <err> <code> print both, exit with <code>
 *  print <text>                         print <text> to stdout (no newline)
 *  sleep <ms>                           sleep, then exit 0
 *  mixed <outMiB> <errKiB>              write that much to stdout and stderr, interleaved
 *  spawn-tree <pidFile> <ms>            spawn a child that inherits our pipes, write "self,child" pids, sleep
 *  stdin-len                            read all of stdin, print its byte count
 *  echo-lines                           echo every stdin line as "echo:<line>"; exit 0 on "quit"
 *  emit-lines <n> <code>                print line-1..line-n, exit with <code>
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "exit" -> exitProcess(args[1].toInt())
        "stdout-and-stderr" -> {
            print(args[1])
            System.out.flush()
            System.err.print(args[2])
            System.err.flush()
            exitProcess(args[3].toInt())
        }
        "print" -> {
            print(args[1])
            System.out.flush()
        }
        "sleep" -> Thread.sleep(args[1].toLong())
        "mixed" -> mixed(args[1].toInt() * 1024 * 1024, args[2].toInt() * 1024)
        "spawn-tree" -> spawnTree(File(args[1]), args[2].toLong())
        "stdin-len" -> println(System.`in`.readAllBytes().size)
        "echo-lines" -> echoLines()
        "emit-lines" -> {
            repeat(args[1].toInt()) { println("line-${it + 1}") }
            System.out.flush()
            exitProcess(args[2].toInt())
        }
        else -> {
            System.err.println("FakeProcessMain: unknown mode ${args.joinToString(" ")}")
            exitProcess(64)
        }
    }
}

private fun mixed(outBytes: Int, errBytes: Int) {
    val chunk = ByteArray(8 * 1024) { 'x'.code.toByte() }
    var outLeft = outBytes
    var errLeft = errBytes
    while (outLeft > 0 || errLeft > 0) {
        if (outLeft > 0) {
            val n = minOf(chunk.size, outLeft)
            System.out.write(chunk, 0, n)
            outLeft -= n
        }
        if (errLeft > 0) {
            val n = minOf(1024, errLeft)
            System.err.write(chunk, 0, n)
            errLeft -= n
        }
    }
    System.out.flush()
    System.err.flush()
}

private fun spawnTree(pidFile: File, millis: Long) {
    val child = ProcessBuilder(
        File(System.getProperty("java.home"), "bin/java").path,
        "-cp",
        System.getProperty("java.class.path"),
        "com.titanicbhai.uiscope.testing.FakeProcessMain",
        "sleep",
        millis.toString(),
    ).redirectOutput(ProcessBuilder.Redirect.INHERIT) // the child holds the parent's pipes open, like a real helper would
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    pidFile.writeText("${ProcessHandle.current().pid()},${child.pid()}")
    Thread.sleep(millis)
}

private fun echoLines() {
    val reader = System.`in`.bufferedReader()
    while (true) {
        val line = reader.readLine() ?: return
        if (line == "quit") return
        println("echo:$line")
        System.out.flush()
    }
}
