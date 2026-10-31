package com.titanicbhai.uiscope.guardrails

import com.titanicbhai.uiscope.testing.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** P3-9: `check` fails when a forbidden pattern is added (one test per rule); the repository passes; closes B10, keeps B2 and B8 away. */
class GuardrailsTest {
    private val engine = "engine/src/main/kotlin/com/titanicbhai/uiscope/adb/Something.kt"
    private val processDir = "engine/src/main/kotlin/com/titanicbhai/uiscope/process/ProcessSupport.kt"

    private fun ids(path: String, text: String) = Guardrails.scan(path, text).map { it.rule.id }.distinct()

    @Test
    fun `the repository passes every rule`() {
        val root = Guardrails.findRoot(Path.of("").toAbsolutePath()) ?: fail("settings.gradle.kts not found above ${Path.of("").toAbsolutePath()}")
        val found = Guardrails.scanRepository(root)
        assertTrue(found.isEmpty(), "Forbidden patterns found:\n" + found.joinToString("\n"))
    }

    @Test
    fun `rule process-builder`() {
        assertEquals(listOf("process-builder"), ids(engine, "val p = ProcessBuilder(\"adb\").start()"))
        assertEquals(listOf("process-builder"), ids(engine, "val p = java.lang.ProcessBuilder(cmd)"))
        assertEquals(listOf("process-builder"), ids("core/src/main/kotlin/x/Y.kt", "Runtime.getRuntime().exec(\"adb\")"))
        assertEquals(emptyList(), ids(processDir, "val builder = ProcessBuilder(cmd)"), "engine/process is the one allowed place")
    }

    @Test
    fun `rule shell-and-automation-tools`() {
        for (text in listOf(
            "launch(\"powershell\", \"-Command\", x)", "val exe = \"powershell.exe\"", "run(\"pwsh\")", "run(\"osascript\", \"-e\", s)", "exec(\"xdotool\", \"key\")",
            "import pyatspi", "args += \"-ExecutionPolicy\"", "val x = 'pwsh'",
        )) {
            assertEquals(listOf("shell-and-automation-tools"), ids(engine, text), text)
        }
        // Generating PowerShell text for the user to copy is a different thing and stays allowed, but only in the code generator.
        val generated = "\"# Run it in Windows PowerShell with the -STA switch (powershell.exe -STA -File script.ps1).\""
        assertEquals(emptyList(), ids("core/src/main/kotlin/com/titanicbhai/uiscope/codegen/CodeGenerator.kt", generated))
        assertEquals(listOf("shell-and-automation-tools"), ids("engine/src/main/kotlin/com/titanicbhai/uiscope/windows/Helper.kt", generated))
        assertEquals(emptyList(), ids(engine, "fun powershell(c: Candidate) = \"# PowerShell with the built-in UI Automation assemblies\""))
    }

    @Test
    fun `rule host-shell`() {
        for (text in listOf(
            "val cmd = \"sh -c ls\"", "run(\"bash\", \"-c\", script)", "exec(\"cmd /c dir\")", "val shell = \"cmd.exe /c %S\"",
        )) {
            assertEquals(listOf("host-shell"), ids(engine, text), text)
        }
        // adb's own device-side "shell" subcommand is not a host shell and stays fine.
        assertEquals(emptyList(), ids(engine, "adb(\"shell\", \"pm\", \"dump\", pkg)"))
        assertEquals(emptyList(), ids(engine, "listOf(\"adb\", \"-s\", serial, \"shell\", \"wm\", \"size\")"))
    }

    @Test
    fun `rule global-input-hooks`() {
        assertEquals(listOf("global-input-hooks"), ids(engine, "import com.github.kwhat.jnativehook.GlobalScreen"))
        assertEquals(listOf("global-input-hooks"), ids("engine/build.gradle.kts", "implementation(\"com.github.kwhat:jnativehook:2.2.2\")"))
        assertEquals(listOf("global-input-hooks"), ids("gradle/libs.versions.toml", "jnativehook = { module = \"com.github.kwhat:jnativehook\" }"))
    }

    @Test
    fun `rule awt-pointer-input`() {
        assertEquals(listOf("awt-pointer-input"), ids(engine, "val p = java.awt.MouseInfo.getPointerInfo()"))
        assertEquals(listOf("awt-pointer-input"), ids(engine, "import java.awt.Robot"))
        assertEquals(listOf("awt-pointer-input"), ids(engine, "val r = Robot()"))
        assertEquals(emptyList(), ids(engine, "val robotics = 1 // MouseInfo is forbidden"))
    }

    @Test
    fun `rule network-outside-gateway`() {
        for (text in listOf("val u = URL(\"https://x\")", "val c = java.net.URL(s).openConnection()", "private val c: HttpClient = x", "val h = conn as HttpURLConnection")) {
            assertEquals(listOf("network-outside-gateway"), ids(engine, text), text)
        }
        val gateway = "engine/src/main/kotlin/com/titanicbhai/uiscope/process/JdkNetworkGateway.kt"
        assertEquals(emptyList(), ids(gateway, "private val client: HttpClient = HttpClient.newBuilder().build()"))
        assertEquals(listOf("network-outside-gateway"), ids("engine/src/main/kotlin/com/titanicbhai/uiscope/update/Other.kt", "HttpClient.newHttpClient()"))
    }

    @Test
    fun `comments and string contents do not count for code rules but strings count for tool names`() {
        val text = "// ProcessBuilder is only used in process/\n/* HttpClient\n   nested /* URL( */ still comment */\nval s = \"new URL(\\\"x\\\")\"\nval t = \"\"\"HttpClient and ProcessBuilder\"\"\""
        assertEquals(emptyList(), ids(engine, text))
        assertEquals(listOf("shell-and-automation-tools"), ids(engine, "val s = \"osascript\" // fine in a comment, not in a string"))
        assertEquals(emptyList(), ids(engine, "// osascript is mentioned in this comment only"))
    }

    @Test
    fun `line numbers are kept and violations name the file`() {
        val v = Guardrails.scan(engine, "package x\n\n// comment\nval a = 1\nval p = ProcessBuilder(\"x\")\n").single()
        assertEquals(5, v.line)
        assertEquals(engine, v.file)
        assertTrue(v.toString().contains("process-builder") && v.toString().contains("$engine:5"))
    }

    @Test
    fun `adding a forbidden pattern to a repository makes the scan fail and removing it makes it pass`() {
        withTempDir { root ->
            Files.writeString(root.resolve("settings.gradle.kts"), "")
            val dir = Files.createDirectories(root.resolve("engine/src/main/kotlin/com/titanicbhai/uiscope/adb"))
            val file = dir.resolve("New.kt")
            Files.writeString(file, "package x\nclass New { fun go() = ProcessBuilder(\"adb\").start() }\n")
            val legacy = Files.createDirectories(root.resolve("legacy/engine/src/main/kotlin"))
            Files.writeString(legacy.resolve("Old.kt"), "val p = ProcessBuilder(\"adb\")")
            val found = Guardrails.scanRepository(root)
            assertEquals(1, found.size, found.joinToString("\n"))
            assertNotNull(found.single().file.takeIf { it.endsWith("adb/New.kt") })
            Files.writeString(file, "package x\nclass New\n")
            assertEquals(emptyList(), Guardrails.scanRepository(root).map { it.toString() })
        }
    }

    @Test
    fun `the blanker keeps newlines and handles character literals and escapes`() {
        val src = "val q = '\"'\nval s = \"a\\\"b ProcessBuilder\"\nval r = ProcessBuilder(1)\n"
        val (code, _) = Guardrails.blank(src)
        assertEquals(src.count { it == '\n' }, code.count { it == '\n' })
        assertEquals(listOf(3), Guardrails.scan(engine, src).map { it.line })
    }
}
