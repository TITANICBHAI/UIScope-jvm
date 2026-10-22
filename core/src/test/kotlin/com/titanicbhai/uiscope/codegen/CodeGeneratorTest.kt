package com.titanicbhai.uiscope.codegen

import com.titanicbhai.uiscope.model.DeviceInfo
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.model.WindowInfo
import com.titanicbhai.uiscope.selector.Candidate
import com.titanicbhai.uiscope.selector.Condition
import com.titanicbhai.uiscope.selector.Selector
import com.titanicbhai.uiscope.selector.SelectorKey
import com.titanicbhai.uiscope.selector.SelectorWarning
import com.titanicbhai.uiscope.selector.Strategy
import com.titanicbhai.uiscope.selector.Tier
import com.titanicbhai.uiscope.selector.neutral
import com.titanicbhai.uiscope.testing.GoldenFiles
import com.titanicbhai.uiscope.testing.HostileStrings
import com.titanicbhai.uiscope.testing.withTempDir
import java.nio.file.Files
import java.util.regex.Pattern
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Appendix G4 (fixes B6, M14): all templates with hostile strings, golden files, and semantic checks where an interpreter exists. */
class CodeGeneratorTest {
    private val android: Target = Target.Android(DeviceInfo("SERIAL-SECRET-1234567890", "Pixel", "14", 34), "com.example")
    private val windows: Target = Target.Windows(WindowInfo("Calculator", "calc.exe", 4242, "XAML"))

    private fun cand(selector: Selector, tier: Tier = Tier.GOOD, reasons: List<String> = listOf("test reason"), warnings: Set<SelectorWarning> = emptySet()) =
        Candidate(Strategy.TEXT, selector, selector.neutral(), 1, tier, reasons, warnings)

    private fun textSelector(value: String) = Selector.Attr(SelectorKey.TEXT, value)

    private fun code(target: CodeTarget, candidate: Candidate, captureTarget: Target = if (target.platform == CodePlatform.WINDOWS) windows else android): CodeResult.Code =
        assertIs<CodeResult.Code>(CodeGenerator.generate(target, candidate, captureTarget), "$target ${candidate.expression}")

    private fun render(target: CodeTarget, candidate: Candidate): String =
        when (val r = CodeGenerator.generate(target, candidate, if (target.platform == CodePlatform.WINDOWS) windows else android)) {
            is CodeResult.Code -> r.code
            is CodeResult.NotAvailable -> "NOT AVAILABLE: ${r.reason}\n"
        }

    private fun hostileSelector(target: CodeTarget, text: String): Selector =
        if (target.platform == CodePlatform.WINDOWS) Selector.Attr(SelectorKey.NAME, text) else textSelector(text)

    // ── goldens ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `hostile strings in every template match the golden files`() {
        for (target in CodeTarget.entries) {
            val text = HostileStrings.all.entries.joinToString("\n") { (name, value) -> "### $name\n" + render(target, cand(hostileSelector(target, value))) }
            GoldenFiles.assertMatches("codegen/${target.name}.hostile.txt", text)
        }
    }

    @Test
    fun `every kind of selector in every template matches the golden files`() {
        val androidSelectors = listOf(
            "reliable id" to cand(Selector.Attr(SelectorKey.IDENTIFIER, "app:id/login_button"), Tier.RELIABLE),
            "content description" to cand(Selector.Attr(SelectorKey.DESCRIPTION, "Close")),
            "id and text" to cand(Selector.Attr(Condition(SelectorKey.IDENTIFIER, "app:id/title"), Condition(SelectorKey.TEXT, "Orders"))),
            "class and text" to cand(Selector.Attr(Condition(SelectorKey.CLASS, "android.widget.Button"), Condition(SelectorKey.TEXT, "OK"))),
            "index" to cand(Selector.Index(Selector.Attr(SelectorKey.IDENTIFIER, "app:id/row"), 3), Tier.NEEDS_INDEX, listOf("Attribute shared by 14 elements; index 3 breaks if the list changes")),
            "anchor" to cand(Selector.Anchor(Selector.Attr(SelectorKey.IDENTIFIER, "app:id/root"), listOf(2, 0)), Tier.FRAGILE, listOf("Path from a unique anchor element")),
            "path" to cand(Selector.Path(ElementPath("0/3/1")), Tier.FRAGILE, listOf("Position in the tree only")),
            "coordinates" to cand(Selector.Coordinates(540, 1200), Tier.FRAGILE, listOf("Screen coordinates: last resort")),
            "dynamic text" to cand(textSelector("12:45"), Tier.FRAGILE, listOf("Unique, but the text looks dynamic"), setOf(SelectorWarning.TEXT_LOCALIZED, SelectorWarning.TEXT_DYNAMIC)),
        )
        val windowsSelectors = listOf(
            "automation id" to cand(Selector.Attr(SelectorKey.IDENTIFIER, "num1Button"), Tier.RELIABLE),
            "control type and name" to cand(Selector.Attr(Condition(SelectorKey.CONTROL_TYPE, "Button"), Condition(SelectorKey.NAME, "Save"))),
            "class and name" to cand(Selector.Attr(Condition(SelectorKey.CLASS, "Button"), Condition(SelectorKey.NAME, "Save"))),
            "index" to cand(Selector.Index(Selector.Attr(SelectorKey.IDENTIFIER, "row"), 1), Tier.NEEDS_INDEX, listOf("Attribute shared by 4 elements; index 1 breaks if the list changes")),
            "anchor" to cand(Selector.Anchor(Selector.Attr(SelectorKey.IDENTIFIER, "pane"), listOf(1, 0)), Tier.FRAGILE, listOf("Path from a unique anchor element")),
            "path" to cand(Selector.Path(ElementPath("0/2/1")), Tier.FRAGILE, listOf("Position in the tree only")),
            "coordinates" to cand(Selector.Coordinates(640, 480), Tier.FRAGILE, listOf("Screen coordinates: last resort")),
        )
        for (target in CodeTarget.entries) {
            val selectors = if (target.platform == CodePlatform.WINDOWS) windowsSelectors else androidSelectors
            val text = selectors.joinToString("\n") { (name, c) -> "### $name\n" + render(target, c) }
            GoldenFiles.assertMatches("codegen/${target.name}.structure.txt", text)
        }
    }

    // ── exact output for the ordinary case (readable regression anchors) ───────────────────────

    @Test
    fun `uiautomator2 and XPath output for a reliable id`() {
        val id = cand(Selector.Attr(SelectorKey.IDENTIFIER, "app:id/login_button"), Tier.RELIABLE)
        val u2 = code(CodeTarget.UIAUTOMATOR2_PYTHON, id)
        assertEquals(
            """
            # uiautomator2: https://github.com/openatx/uiautomator2
            import uiautomator2 as u2

            d = u2.connect()  # several devices attached? u2.connect("<serial>") or set ANDROID_SERIAL
            d.app_start('com.example')
            d(resourceId='app:id/login_button').click()
            """.trimIndent() + "\n",
            u2.code,
        )
        assertEquals("d(resourceId='app:id/login_button')", u2.selectorText)
        val xpath = code(CodeTarget.XPATH, id)
        assertEquals("//*[@resource-id='app:id/login_button']\n", xpath.code)
        assertEquals("//*[@resource-id='app:id/login_button']", xpath.selectorText)
    }

    @Test
    fun `kotlin and maestro output for text with an apostrophe`() {
        val c = cand(textSelector("Don't allow"))
        assertTrue(code(CodeTarget.UIAUTOMATOR_KOTLIN, c).code.contains("device.findObject(By.text(\"Don't allow\")).click()"))
        val maestro = code(CodeTarget.MAESTRO, c)
        assertTrue(maestro.code.contains("appId: \"com.example\"\n---\n- launchApp\n- tapOn:\n    text: \"Don't allow\""), maestro.code)
    }

    // ── semantic checks ───────────────────────────────────────────────────────────────────────

    @Test
    fun `python outputs compile and the selector arguments evaluate to the original text`() {
        EscapersTest.python ?: return println("SKIPPED: no python on this machine (CI has it)")
        withTempDir { dir ->
            var compiled = 0
            for (target in listOf(CodeTarget.UIAUTOMATOR2_PYTHON, CodeTarget.APPIUM_PYTHON, CodeTarget.PYWINAUTO)) {
                for ((name, value) in HostileStrings.all) {
                    val result = CodeGenerator.generate(target, cand(hostileSelector(target, value)), if (target.platform == CodePlatform.WINDOWS) windows else android)
                    val code = assertIs<CodeResult.Code>(result, "$target $name").code
                    val file = dir.resolve("${target.name}-$name.py")
                    Files.writeString(file, code)
                    val (exit, out) = EscapersTest.runPython("-m", "py_compile", file.toString())
                    assertEquals(0, exit, "$target $name did not compile: ${out.toString(Charsets.UTF_8)}\n$code")
                    compiled++
                }
            }
            assertTrue(compiled >= 3 * HostileStrings.all.size)
            // uiautomator2: run the selector with a stand-in `d` and read back what the keyword argument contained
            for ((name, value) in HostileStrings.common) {
                val selector = code(CodeTarget.UIAUTOMATOR2_PYTHON, cand(textSelector(value))).selectorText
                val script = "class D:\n    def __call__(self, **kw):\n        print(' '.join('%x' % ord(c) for c in kw['text']))\nd = D()\n$selector\n"
                val (exit, out) = EscapersTest.runPython(script = script, args = arrayOf("-"))
                assertEquals(0, exit, "$name: ${out.toString(Charsets.UTF_8)}")
                assertEquals(value.codePoints().toArray().joinToString(" ") { it.toString(16) }, out.toString(Charsets.UTF_8).trim(), name)
            }
            // Appium: the locator value is the XPath for the text
            for ((name, value) in HostileStrings.common.filterValues { it.isNotEmpty() }) {
                val selector = code(CodeTarget.APPIUM_PYTHON, cand(textSelector(value))).selectorText
                val script = "class B:\n    XPATH = 'xpath'\nAppiumBy = B\nclass Drv:\n    def find_element(self, by, value):\n        print(' '.join('%x' % ord(c) for c in value))\ndriver = Drv()\n$selector\n"
                val (exit, out) = EscapersTest.runPython(script = script, args = arrayOf("-"))
                assertEquals(0, exit, "$name: ${out.toString(Charsets.UTF_8)}")
                val expected = XPath.render(textSelector(value))!!
                assertEquals(expected.codePoints().toArray().joinToString(" ") { it.toString(16) }, out.toString(Charsets.UTF_8).trim(), name)
            }
        }
    }

    @Test
    fun `maestro output is valid YAML whose regular expressions match only the intended text`() {
        for ((name, value) in HostileStrings.common + HostileStrings.controls) {
            val docs = Yaml().loadAll(code(CodeTarget.MAESTRO, cand(textSelector(value))).code).toList()
            assertEquals(2, docs.size, name)
            @Suppress("UNCHECKED_CAST")
            val header = docs[0] as Map<String, Any?>
            assertEquals("com.example", header["appId"], name)
            @Suppress("UNCHECKED_CAST")
            val steps = docs[1] as List<Any?>
            assertEquals("launchApp", steps[0], name)
            @Suppress("UNCHECKED_CAST")
            val tap = (steps[1] as Map<String, Map<String, Any?>>)["tapOn"]!!
            val regex = tap["text"] as String
            assertTrue(Pattern.matches(regex, value), "$name: '$regex' must match the original")
            assertFalse(Pattern.matches(regex, value + "x"), "$name: '$regex' must not match a longer text")
        }
        val index = Yaml().loadAll(code(CodeTarget.MAESTRO, cand(Selector.Index(Selector.Attr(SelectorKey.IDENTIFIER, "app:id/row"), 3), Tier.NEEDS_INDEX)).code).toList()
        @Suppress("UNCHECKED_CAST")
        val tap = ((index[1] as List<Any?>)[1] as Map<String, Map<String, Any?>>)["tapOn"]!!
        assertEquals(3, tap["index"])
        assertTrue(Pattern.matches(tap["id"] as String, "app:id/row"))
        val point = Yaml().loadAll(code(CodeTarget.MAESTRO, cand(Selector.Coordinates(540, 1200), Tier.FRAGILE)).code).toList()
        @Suppress("UNCHECKED_CAST")
        assertEquals("540, 1200", ((point[1] as List<Any?>)[1] as Map<String, Map<String, Any?>>)["tapOn"]!!["point"])
    }

    @Test
    fun `maestro matches a content description through text and says so`() {
        val r = code(CodeTarget.MAESTRO, cand(Selector.Attr(SelectorKey.DESCRIPTION, "Close")))
        assertTrue(r.code.contains("    text: \"Close\""), r.code)
        assertFalse(r.code.contains("description:"), "Maestro has no description selector")
        assertTrue(r.code.contains("accessibility text"), r.code)
    }

    // ── what a target cannot do, serials, verdict comments ───────────────────────────────────────

    @Test
    fun `targets that cannot express a selector say why instead of emitting wrong code`() {
        val path = cand(Selector.Path(ElementPath("0/1")), Tier.FRAGILE)
        val coords = cand(Selector.Coordinates(1, 2), Tier.FRAGILE)
        listOf(CodeTarget.UIAUTOMATOR_KOTLIN, CodeTarget.MAESTRO).forEach { assertIs<CodeResult.NotAvailable>(CodeGenerator.generate(it, path, android), "$it path") }
        listOf(CodeTarget.XPATH, CodeTarget.APPIUM_JAVA, CodeTarget.APPIUM_PYTHON).forEach { assertIs<CodeResult.NotAvailable>(CodeGenerator.generate(it, coords, android), "$it coordinates") }
        listOf(CodeTarget.POWERSHELL_UIA).forEach {
            assertIs<CodeResult.NotAvailable>(CodeGenerator.generate(it, path, windows))
            assertIs<CodeResult.NotAvailable>(CodeGenerator.generate(it, coords, windows))
        }
        assertIs<CodeResult.NotAvailable>(CodeGenerator.generate(CodeTarget.MAESTRO, cand(Selector.Attr(SelectorKey.CLASS, "android.widget.Button")), android), "no class selector in Maestro")
        assertIs<CodeResult.NotAvailable>(CodeGenerator.generate(CodeTarget.FLAUI, cand(Selector.Attr(SelectorKey.CONTROL_TYPE, "Button; evil()")), windows), "control types must be identifiers")
        assertIs<CodeResult.NotAvailable>(CodeGenerator.generate(CodeTarget.PYWINAUTO, cand(textSelector("x")), android), "platform mismatch")
        assertIs<CodeResult.NotAvailable>(CodeGenerator.generate(CodeTarget.UIAUTOMATOR2_PYTHON, cand(textSelector("x")), windows), "platform mismatch")
    }

    @Test
    fun `no generated code or comment contains the device serial`() {
        for (target in CodeTarget.forCapture(android)) {
            for ((_, value) in HostileStrings.common) {
                (CodeGenerator.generate(target, cand(textSelector(value), Tier.FRAGILE), android) as? CodeResult.Code)?.let {
                    assertFalse(it.code.contains("SERIAL-SECRET"), "$target leaked the serial")
                }
            }
        }
        assertEquals(CodeTarget.entries.filter { it.platform == CodePlatform.ANDROID }, CodeTarget.forCapture(android))
        assertEquals(CodeTarget.entries.filter { it.platform == CodePlatform.WINDOWS }, CodeTarget.forCapture(windows))
    }

    @Test
    fun `the verdict is a comment only when the tier is below Good and never contains user text`() {
        val comments = mapOf(
            CodeTarget.UIAUTOMATOR2_PYTHON to "# ", CodeTarget.UIAUTOMATOR_KOTLIN to "// ", CodeTarget.APPIUM_JAVA to "// ", CodeTarget.APPIUM_PYTHON to "# ",
            CodeTarget.MAESTRO to "# ", CodeTarget.PYWINAUTO to "# ", CodeTarget.FLAUI to "// ", CodeTarget.AUTOHOTKEY_V2 to "; ", CodeTarget.POWERSHELL_UIA to "# ",
        )
        for ((target, marker) in comments) {
            val selector = Selector.Attr(SelectorKey.IDENTIFIER, "x")
            val good = code(target, cand(selector, Tier.GOOD)).code
            val reliable = code(target, cand(selector, Tier.RELIABLE)).code
            val index = code(target, cand(Selector.Index(selector, 2), Tier.NEEDS_INDEX, listOf("Attribute shared by 14 elements; index 2 breaks if the list changes"))).code
            val fragile = code(target, cand(selector, Tier.FRAGILE, listOf("Unique, but the text looks dynamic"), setOf(SelectorWarning.TEXT_DYNAMIC))).code
            assertFalse(good.contains("Selector quality"), "$target Good")
            assertFalse(reliable.contains("Selector quality"), "$target Reliable")
            assertTrue(index.contains("${marker}Selector quality: Needs index. Attribute shared by 14 elements; index 2 breaks if the list changes."), "$target index:\n$index")
            assertTrue(fragile.contains("${marker}Selector quality: Fragile."), "$target fragile")
            assertTrue(fragile.contains("text looks dynamic (digits/time)"), "$target warning")
        }
        assertFalse(code(CodeTarget.XPATH, cand(Selector.Attr(SelectorKey.IDENTIFIER, "x"), Tier.FRAGILE)).code.contains("Selector quality"), "XPath has no comments")
    }

    @Test
    fun `windows templates connect to the captured window`() {
        val c = cand(Selector.Attr(SelectorKey.IDENTIFIER, "num1Button"), Tier.RELIABLE)
        assertTrue(code(CodeTarget.PYWINAUTO, c).code.contains("connect(process=4242)"))
        assertTrue(code(CodeTarget.PYWINAUTO, c).code.contains("window = app.window(title='Calculator')"))
        assertTrue(code(CodeTarget.FLAUI, c).code.contains("Application.Attach(4242)"))
        assertTrue(code(CodeTarget.AUTOHOTKEY_V2, c).code.contains("UIA.ElementFromHandle(\"ahk_pid 4242\")"))
        assertTrue(code(CodeTarget.POWERSHELL_UIA, c).code.contains("ProcessIdProperty, 4242"))
        val noPid = Target.Windows(WindowInfo("", "calc.exe", null))
        assertTrue((CodeGenerator.generate(CodeTarget.PYWINAUTO, c, noPid) as CodeResult.Code).code.contains("connect(path='calc.exe')"))
        assertTrue((CodeGenerator.generate(CodeTarget.PYWINAUTO, c, noPid) as CodeResult.Code).code.contains("window = app.top_window()"))
        assertTrue((CodeGenerator.generate(CodeTarget.FLAUI, c, noPid) as CodeResult.Code).code.contains("Application.Attach(\"calc.exe\")"))
    }
}
