package com.titanicbhai.uiscope.export

import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementFlags
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.model.ScreenInfo
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.model.WindowInfo
import com.titanicbhai.uiscope.testing.SecureXml
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.capture
import com.titanicbhai.uiscope.testing.TestTrees.n
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Appendix G2 (XML half): exports parse with a secure factory and text survives. */
class XmlExporterTest {
    private val androidAttributes = setOf(
        "index", "text", "resource-id", "class", "package", "content-desc", "checkable", "checked", "clickable", "enabled",
        "focusable", "focused", "scrollable", "long-clickable", "password", "selected", "bounds",
    )

    private fun firstNode(xml: String): Element = SecureXml.children(SecureXml.parse(xml)).first()

    @Test
    fun `awkward text survives and illegal characters are removed`() {
        val everyControl = (0..0x1F).joinToString("") { it.toChar().toString() }
        val awkward = "a${everyControl}b \"quoted\" 'single' & <tag> back\\slash 😀 \uD800 \uFFFE\uFFFF end"
        // allowed controls (tab, LF, CR) stay, the rest of U+0000–U+001F, the lone surrogate and U+FFFE/F go
        val expected = "a\t\n\rb \"quoted\" 'single' & <tag> back\\slash 😀   end"
        val xml = XmlExporter.export(capture(n("android.widget.TextView", text = awkward, description = awkward, identifier = "id/$awkward")))
        val node = firstNode(xml)
        assertEquals(expected, node.getAttribute("text"))
        assertEquals(expected, node.getAttribute("content-desc"))
        assertEquals("id/$expected", node.getAttribute("resource-id"))
    }

    @Test
    fun `newlines and tabs in attributes are character references`() {
        val xml = XmlExporter.export(capture(n(text = "line one\nline two\ttab\r")))
        assertTrue(xml.contains("text=\"line one&#10;line two&#9;tab&#13;\""), xml)
        assertEquals("line one\nline two\ttab\r", firstNode(xml).getAttribute("text"))
    }

    @Test
    fun `android export has UIAutomator's real attribute set and hierarchy root`() {
        val flags = ElementFlags(enabled = false, clickable = true, checkable = true, checked = true, password = true, scrollable = true)
        val cap = capture(
            n(
                "android.widget.FrameLayout",
                bounds = Bounds(0, 0, 1080, 2400),
                children = listOf(
                    n("a.B", identifier = "pkg:id/x", flags = flags, properties = mapOf("package" to "com.app"), bounds = Bounds(10, 20, 30, 40)),
                    n("a.C", properties = mapOf("index" to "7")),
                ),
            ),
        )
        val xml = XmlExporter.export(cap)
        assertTrue(xml.startsWith("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<hierarchy rotation=\"0\">"), xml)
        val doc = SecureXml.parse(xml)
        assertEquals("hierarchy", doc.tagName)
        val root = SecureXml.children(doc).single()
        val kids = SecureXml.children(root)
        val b = kids[0]
        assertEquals(androidAttributes, (0 until b.attributes.length).map { b.attributes.item(it).nodeName }.toSet())
        assertEquals("[10,20][40,60]", b.getAttribute("bounds"))
        assertEquals("com.app", b.getAttribute("package"))
        assertEquals("0", b.getAttribute("index"))
        assertEquals("7", kids[1].getAttribute("index"), "a stored index property wins over the sibling position")
        listOf("checkable" to "true", "checked" to "true", "clickable" to "true", "enabled" to "false", "password" to "true", "scrollable" to "true", "focused" to "false")
            .forEach { (name, value) -> assertEquals(value, b.getAttribute(name), name) }
        assertEquals("[0,0][1080,2400]", root.getAttribute("bounds"))
    }

    @Test
    fun `landscape captures export rotation 1`() {
        val xml = XmlExporter.export(capture(listOf(n()), screen = ScreenInfo(2400, 1080)))
        assertEquals("1", SecureXml.parse(xml).getAttribute("rotation"))
    }

    @Test
    fun `tree structure maps one to one onto the xml`() {
        val cap = capture(n("r", children = listOf(n("a", children = listOf(n("a1"), n("a2"))), n("b"))))
        fun pathsOf(e: Element, path: String, acc: MutableList<String>) {
            SecureXml.children(e).forEachIndexed { i, child ->
                val p = if (path.isEmpty()) "$i" else "$path/$i"
                acc += p + "=" + child.getAttribute("class")
                pathsOf(child, p, acc)
            }
        }
        val actual = mutableListOf<String>()
        pathsOf(SecureXml.parse(XmlExporter.export(cap)), "", actual)
        assertEquals(listOf("0=r", "0/0=a", "0/0/0=a1", "0/0/1=a2", "0/1=b"), actual)
        assertEquals(actual.size, cap.roots.sumOf { countNodes(it) })
    }

    private fun countNodes(node: com.titanicbhai.uiscope.model.ElementNode): Int = 1 + node.children.sumOf(::countNodes)

    @Test
    fun `windows export names the window and uses UI Automation names`() {
        val window = WindowInfo("Untitled \"1\" - <Notepad> & co", "notepad.exe", 4242, "Win32")
        val cap = capture(
            listOf(n("Edit", name = "Text editor", identifier = "15", properties = mapOf("ControlType" to "Edit"), text = "hello", flags = ElementFlags(offscreen = true))),
            target = Target.Windows(window),
        )
        val xml = XmlExporter.export(cap)
        val doc = SecureXml.parse(xml)
        assertEquals("windows", doc.getAttribute("platform"))
        assertEquals(window.title, doc.getAttribute("window"))
        assertEquals("notepad.exe", doc.getAttribute("process"))
        val node = SecureXml.children(doc).single()
        assertEquals("Text editor", node.getAttribute("name"))
        assertEquals("15", node.getAttribute("automation-id"))
        assertEquals("Edit", node.getAttribute("control-type"))
        assertEquals("true", node.getAttribute("offscreen"))
        assertEquals("hello", node.getAttribute("text"))
    }

    @Test
    fun `an empty capture is a valid document and path ids are never needed`() {
        val xml = XmlExporter.export(capture(emptyList()))
        assertEquals(0, SecureXml.children(SecureXml.parse(xml)).size)
        assertEquals(ElementPath("0"), ElementPath.root())
    }

    @Test
    fun `10k nodes export and parse quickly`() {
        val big = TestTrees.capture(TestTrees.balanced(10_000))
        val start = System.nanoTime()
        val xml = XmlExporter.export(big)
        val exportMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(10_000, SecureXml.parse(xml).getElementsByTagName("node").length)
        assertTrue(exportMs < 2_000, "exporting 10k nodes took $exportMs ms")
    }
}
