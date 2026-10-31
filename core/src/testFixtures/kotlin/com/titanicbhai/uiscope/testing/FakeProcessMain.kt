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
 *  helper [flags]                       the fake Windows helper (Appendix J over stdio); flags:
 *                                         bad-version   hello answers protocol 2
 *                                         no-hello      hello is never answered
 *                                         slow          every response is delayed 1.5 s
 *                                         malformed     a malformed line precedes every response
 *                                       special request types: "crash" exits, "events" emits event-a/b/c then ok,
 *                                       "never" is never answered.
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
        "helper" -> fakeHelper(args.drop(1).toSet())
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

/** The fake Windows helper: speaks Appendix J over stdio for the client tests (identical behaviour on every OS). */
private fun fakeHelper(flags: Set<String>) {
    val idRegex = Regex("\"id\"\\s*:\\s*(\\d+)")
    val typeRegex = Regex("\"type\"\\s*:\\s*\"([a-zA-Z]+)\"")
    val registeredHotkeys = mutableSetOf<String>()
    val reader = System.`in`.bufferedReader()
    while (true) {
        val line = reader.readLine() ?: return // stdin closed: the helper exits, like the real one
        val id = idRegex.find(line)?.groupValues?.get(1) ?: continue
        val type = typeRegex.find(line)?.groupValues?.get(1) ?: continue
        try {
            dispatchHelperRequest(flags, id, type, line, registeredHotkeys)
        } catch (e: Throwable) {
            // Throwable on purpose: an Error here (image decoding, AWT init) must not silently kill the helper.
            val message = (e.message ?: e.javaClass.name).replace("\\", "\\\\").replace("\"", "\\\"")
            println("{\"id\":$id,\"ok\":false,\"error\":{\"code\":\"FAKE_HELPER_ERROR\",\"message\":\"${e.javaClass.simpleName}: $message\"}}")
            System.out.flush()
        }
    }
}

private fun dispatchHelperRequest(
    flags: Set<String>,
    id: String,
    type: String,
    line: String,
    registeredHotkeys: MutableSet<String>,
) {
    if (flags.contains("malformed")) println("{ this line is not json")
    when (type) {
        "crash" -> exitProcess(7)
        "never" -> while (true) Thread.sleep(1_000)
        "events" -> {
            for (name in listOf("event-a", "event-b", "event-c")) {
                println("{\"event\":\"$name\",\"id\":$id}")
                System.out.flush()
                Thread.sleep(50)
            }
            println("{\"id\":$id,\"ok\":true}")
            System.out.flush()
        }
        "startPick" -> {
            // Pick-shaped events for the EngineWindowsPort translation test: hover, then the result, then the ok.
            println("{\"event\":\"pickHover\",\"element\":{\"controlType\":\"Button\",\"name\":\"OK\",\"rect\":{\"x\":1,\"y\":2,\"width\":3,\"height\":4}}}")
            System.out.flush()
            println("{\"event\":\"pickResult\",\"hwnd\":\"0x00120a3c\",\"element\":{\"automationId\":\"app:id/ok\"," +
                "\"runtimeId\":\"1 42 2\",\"name\":\"OK\",\"controlType\":\"Button\"," +
                "\"rect\":{\"x\":500,\"y\":450,\"width\":200,\"height\":60}}}")
            System.out.flush()
            println("{\"id\":$id,\"ok\":true}")
            System.out.flush()
        }
        // Stateful like the real helper: the same combination cannot be registered twice (plan P3-6).
        "registerHotkey" -> {
            val modifiers = Regex("\"modifiers\"\\s*:\\s*\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
            val key = Regex("\"key\"\\s*:\\s*\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
            if ("$modifiers+$key" in registeredHotkeys) {
                println("{\"id\":$id,\"ok\":false,\"error\":{\"code\":\"HOTKEY_IN_USE\",\"message\":\"that shortcut is taken\"}}")
                System.out.flush()
            } else {
                registeredHotkeys += "$modifiers+$key"
                println("{\"id\":$id,\"ok\":true}")
                System.out.flush()
            }
        }
        "unregisterHotkey" -> {
            registeredHotkeys.clear()
            println("{\"id\":$id,\"ok\":true}")
            System.out.flush()
        }
            "listWindows" -> {
            println("{\"id\":$id,\"ok\":true,\"windows\":[" +
                "{\"hwnd\":\"0x00120A3C\",\"title\":\"Untitled - Notepad\",\"processName\":\"notepad.exe\",\"pid\":1234,\"isElevated\":false}," +
                "{\"hwnd\":\"0x0000BEEF\",\"title\":\"Task Manager\",\"processName\":\"Taskmgr.exe\",\"pid\":99,\"isElevated\":true}]}")
            System.out.flush()
        }
        "captureWindow" -> {
            // A real (tiny) PNG plus a 2-node Appendix B tree; the origin is the window's top-left.
            // No core imports here: the helper JVM's classpath is testFixtures + stdlib only.
            val png = java.io.File.createTempFile("uiscope-fake-helper", ".png")
            val image = java.awt.image.BufferedImage(800, 600, java.awt.image.BufferedImage.TYPE_INT_RGB)
            javax.imageio.ImageIO.write(image, "png", png)
            val escapedPath = png.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
            println("{\"id\":$id,\"ok\":true,\"window\":{\"hwnd\":\"0x00120a3c\",\"title\":\"Untitled - Notepad\"," +
                "\"processName\":\"notepad.exe\",\"pid\":1234,\"isElevated\":false," +
                "\"rect\":{\"x\":100,\"y\":50,\"width\":800,\"height\":600}}," +
                "\"screenshot\":{\"path\":\"$escapedPath\",\"width\":800,\"height\":600,\"originX\":100,\"originY\":50}," +
                "\"roots\":[{\"automationId\":\"app:id/root\",\"name\":\"Root\",\"controlType\":\"Window\"," +
                "\"className\":\"Window\",\"runtimeId\":\"1 42 1\"," +
                "\"rect\":{\"x\":100,\"y\":50,\"width\":800,\"height\":600},\"children\":[" +
                "{\"automationId\":\"app:id/ok\",\"name\":\"OK\",\"controlType\":\"Button\",\"className\":\"Button\"," +
                "\"runtimeId\":\"1 42 2\",\"rect\":{\"x\":500,\"y\":450,\"width\":200,\"height\":60}}]}],\"truncated\":false}")
            System.out.flush()
        }
        "hello" -> {
            val protocol = if (flags.contains("bad-version")) 2 else 1
            println("{\"id\":$id,\"ok\":true,\"protocol\":$protocol,\"helperVersion\":\"0.1.0\"}")
            System.out.flush()
        }
        else -> {
            if (flags.contains("slow")) Thread.sleep(1_500)
            println("{\"id\":$id,\"ok\":true,\"type\":\"$type\"}")
            System.out.flush()
        }
    }
}
