package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.export.OutlineExporter
import com.titanicbhai.uiscope.export.XmlExporter
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.model.HitTest
import com.titanicbhai.uiscope.selector.SelectorEngine
import com.titanicbhai.uiscope.testing.Fixtures
import com.titanicbhai.uiscope.testing.GoldenFiles
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Appendix G1 (fixes B1, M16). */
class UiAutomatorParserTest {
    private fun count(nodes: List<ElementNode>): Int = nodes.sumOf { 1 + count(it.children) }

    private fun parse(name: String) = UiAutomatorParser.parse(Fixtures.androidDump(name))

    private fun capture(result: ParseResult) = Capture("fixture", Instant.EPOCH, TestTrees.androidTarget(), result.screen, null, result.roots, result.warnings)

    @Test
    fun `clean dump gives two nodes`() {
        val r = parse("g1-clean.xml").assertSuccess()
        assertEquals(2, count(r.roots))
        assertEquals("android.widget.FrameLayout", r.roots.single().className)
        val title = r.roots.single().children.single()
        assertEquals("Hello", title.text)
        assertEquals("com.app:id/title", title.identifier)
        assertEquals(Bounds(0, 0, 100, 100), title.bounds)
        assertEquals(ElementPath("0/0"), title.path)
        assertEquals(1080, r.screen.width)
        assertEquals(2400, r.screen.height)
        assertEquals(0, r.screen.rotation)
    }

    @Test
    fun `the uiautomator suffix does not make the tree empty`() {
        assertEquals(2, count(parse("g1-trailing-suffix.xml").assertSuccess().roots))
    }

    @Test
    fun `crlf and a leading ERROR line are tolerated`() {
        assertEquals(2, count(parse("g1-crlf.xml").assertSuccess().roots))
        assertEquals(2, count(parse("error-prefixed.txt").assertSuccess().roots))
    }

    @Test
    fun `a byte order mark and illegal xml characters are tolerated`() {
        val clean = Fixtures.androidDump("g1-trailing-suffix.xml")
        assertEquals(2, count(UiAutomatorParser.parse("\uFEFF$clean").assertSuccess().roots))
        val dirty = clean.replace("text=\"Hello\"", "text=\"He\u0001l\u0008lo\uFFFE\"")
        assertEquals("Hello", UiAutomatorParser.parse(dirty).assertSuccess().roots.single().children.single().text)
    }

    @Test
    fun `text outside the xml is classified when there is no tree`() {
        UiAutomatorParser.parse(Fixtures.androidDump("error-null-root.txt")).assertFailure(ErrorCodes.DUMP_NULL_ROOT)
        UiAutomatorParser.parse("ERROR: could not get idle state.\n").assertFailure(ErrorCodes.DUMP_IDLE_TIMEOUT)
        UiAutomatorParser.parse("java.lang.IllegalStateException: UiAutomation not connected, UiAutomation@1[id=-1]").assertFailure(ErrorCodes.UIAUTOMATOR_BUSY)
        UiAutomatorParser.parse("UiAutomationService android.accessibilityservice.IAccessibilityServiceClient already registered!").assertFailure(ErrorCodes.UIAUTOMATOR_BUSY)
        UiAutomatorParser.parse("").assertFailure(ErrorCodes.PARSE_FAILED)
        UiAutomatorParser.parse("something unrelated").assertFailure(ErrorCodes.PARSE_FAILED)
    }

    @Test
    fun `malformed input is a typed error and never an empty list`() {
        val good = Fixtures.androidDump("g1-trailing-suffix.xml")
        val malformed = listOf(
            good.replace("</node></hierarchy>", "</hierarchy>"),                      // mismatched tags
            good.replace("bounds=\"[0,0][100,100]\" /", "bounds=\"[0,0][100,100] /"),   // broken attribute
            good.substringBefore("</hierarchy>"),                                    // cut off
            "<hierarchy rotation=\"0\"></hierarchy>",                                // no elements
            "<hierarchy rotation=\"0\"/></hierarchy>",
        )
        for (input in malformed) {
            val error = UiAutomatorParser.parse(input).assertFailure(ErrorCodes.PARSE_FAILED)
            assertFalse(error.detail.orEmpty().contains("Hello"), "the detail must not echo screen content: ${error.detail}")
        }
    }

    @Test
    fun `a DOCTYPE or external entity is rejected`() {
        val evil = "<?xml version='1.0'?><!DOCTYPE hierarchy [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><hierarchy rotation=\"0\"><node index=\"0\" text=\"&x;\" class=\"a.B\" bounds=\"[0,0][1,1]\"/></hierarchy>"
        UiAutomatorParser.parse(evil).assertFailure(ErrorCodes.PARSE_FAILED)
    }

    @Test
    fun `absurd nesting is refused instead of overflowing the stack`() {
        val open = "<node index=\"0\" text=\"\" class=\"a.B\" bounds=\"[0,0][1,1]\">".repeat(1_000)
        val deep = "<hierarchy rotation=\"0\">$open${"</node>".repeat(1_000)}</hierarchy>"
        UiAutomatorParser.parse(deep).assertFailure(ErrorCodes.PARSE_FAILED)
    }

    @Test
    fun `every attribute is kept`() {
        val r = parse("list-duplicate-ids.xml").assertSuccess()
        val index = ElementIndex(capture(r))
        assertEquals(22, index.size)
        val row = index.byPath.getValue(ElementPath("0/0/1/0"))
        assertTrue(row.flags.clickable && row.flags.focusable)
        assertEquals("app".let { "com.example.shop" }, row.properties["package"])
        assertEquals("0", row.properties["index"])
        assertTrue(row.properties.containsKey("drawing-order") && row.properties["display-id"] == "0")
        assertFalse(row.properties.containsKey("hint"), "an empty hint is not recorded")
        val list = index.byPath.getValue(ElementPath("0/0/1"))
        assertTrue(list.flags.scrollable)
        assertNull(index.byPath.getValue(ElementPath("0/0/2")).text, "an empty text is null")
        assertEquals("Smith & Sons", index.nodes.first { it.text?.startsWith("Smith") == true }.text)
    }

    @Test
    fun `checked state and rotation follow the dump`() {
        val xml = "<hierarchy rotation=\"3\"><node index=\"0\" text=\"\" resource-id=\"\" class=\"a.Switch\" package=\"p\" content-desc=\"\" checkable=\"true\" checked=\"true\" clickable=\"true\" enabled=\"false\" focusable=\"true\" focused=\"true\" scrollable=\"false\" long-clickable=\"true\" password=\"true\" selected=\"true\" bounds=\"[10,20][110,60]\"/></hierarchy>"
        val r = UiAutomatorParser.parse(xml).assertSuccess()
        val f = r.roots.single().flags
        assertEquals(3, r.screen.rotation)
        assertTrue(f.checkable && f.checked == true && f.clickable && !f.enabled && f.focusable && f.focused && f.longClickable && f.password && f.selected)
        val plain = UiAutomatorParser.parse(xml.replace("checkable=\"true\" checked=\"true\"", "checkable=\"false\" checked=\"false\"")).assertSuccess().roots.single().flags
        assertNull(plain.checked, "no checkable state means no checked value")
    }

    @Test
    fun `a locked or system only screen carries the secure window warning`() {
        val keyguard = "<hierarchy rotation=\"0\"><node index=\"0\" text=\"\" class=\"android.widget.FrameLayout\" package=\"com.android.systemui\" bounds=\"[0,0][1080,2400]\"/></hierarchy>"
        val warnings = UiAutomatorParser.parse(keyguard).assertSuccess().warnings
        assertEquals(listOf(ErrorCodes.SECURE_WINDOW), warnings.map { it.code })
        assertTrue(parse("g1-clean.xml").assertSuccess().warnings.isEmpty())
    }

    @Test
    fun `a 3000 node tree parses in under 200 ms`() {
        val big = XmlExporter.export(TestTrees.capture(TestTrees.balanced(3_000)))
        repeat(3) { UiAutomatorParser.parse(big).assertSuccess() }
        val best = (1..5).minOf {
            val start = System.nanoTime()
            val result = UiAutomatorParser.parse(big)
            val ms = (System.nanoTime() - start) / 1_000_000.0
            assertEquals(3_000, count((result as Outcome.Success).value.roots))
            ms
        }
        assertTrue(best < 200.0, "parsing 3000 nodes took $best ms (limit 200 ms)")
    }

    // ── the P0-7 fixtures end to end: parse → committed golden → export round trip → selector engine ──

    private val fixtures = listOf("g1-clean.xml", "g1-trailing-suffix.xml", "g1-crlf.xml", "error-prefixed.txt", "list-duplicate-ids.xml", "dialog-over-activity.xml")

    @Test
    fun `converted fixtures match their committed goldens`() {
        for (name in fixtures) {
            val cap = capture(parse(name).assertSuccess())
            GoldenFiles.assertMatches("android/$name.outline.txt", OutlineExporter.export(cap))
            GoldenFiles.assertMatches("android/$name.export.xml", XmlExporter.export(cap))
        }
    }

    @Test
    fun `export then parse gives back the same tree`() {
        for (name in fixtures) {
            val first = capture(parse(name).assertSuccess())
            val again = capture(UiAutomatorParser.parse(XmlExporter.export(first)).assertSuccess())
            assertEquals(first.roots, again.roots, "round trip of $name")
            assertEquals(first.screen, again.screen, name)
        }
    }

    @Test
    fun `the selector engine produces a unique best selector for every element of every fixture`() {
        for (name in fixtures) {
            val index = ElementIndex(capture(parse(name).assertSuccess()))
            for (node in index.nodes) {
                val report = SelectorEngine.suggest(index, node)
                assertEquals(1, report.best.matches, "$name ${node.path}")
            }
        }
    }

    @Test
    fun `the dialog window covers the activity underneath`() {
        val index = ElementIndex(capture(parse("dialog-over-activity.xml").assertSuccess()))
        assertEquals(2, index.capture.roots.size)
        assertEquals("com.android.permissioncontroller:id/permission_message", HitTest.hit(index, 540, 950)?.identifier)
        assertEquals("com.example.maps:id/map_view", HitTest.hit(index, 540, 600)?.identifier, "outside the dialog the map is hit")
    }
}
