package com.titanicbhai.uiscope.testing

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sanity checks for the synthetic fixtures with a plain JDK parser — NOT the app's parser (that arrives in P2-3).
 * They protect the fixtures from accidental edits (line endings, suffix, structure) that later tests rely on.
 */
class AndroidFixturesTest {
    private val suffix = "UI hierchary dumped to: /dev/stdout"

    private fun xmlSpan(raw: String): String? {
        val start = raw.indexOf("<?xml").takeIf { it >= 0 } ?: raw.indexOf("<hierarchy").takeIf { it >= 0 } ?: return null
        val endTag = "</hierarchy>"
        val end = raw.indexOf(endTag).takeIf { it >= 0 } ?: return null
        return raw.substring(start, end + endTag.length)
    }

    private fun parse(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement
    }

    private fun nodeCount(root: Element) = root.getElementsByTagName("node").length

    private fun directNodes(parent: Element): List<Element> =
        (0 until parent.childNodes.length).map { parent.childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == "node" }

    @Test
    fun `the G1 samples contain exactly two nodes`() {
        listOf("g1-clean.xml", "g1-trailing-suffix.xml", "g1-crlf.xml", "error-prefixed.txt").forEach { name ->
            val root = parse(xmlSpan(Fixtures.androidDump(name)) ?: error("$name has no XML"))
            assertEquals("hierarchy", root.tagName, name)
            assertEquals(2, nodeCount(root), name)
        }
    }

    @Test
    fun `trailing suffix sample is the plan's G1 line and ends with the standard message`() {
        val raw = Fixtures.androidDump("g1-trailing-suffix.xml")
        assertTrue(raw.endsWith("</hierarchy>$suffix\n"), "suffix must follow the XML directly, then a newline")
        assertEquals(Fixtures.androidDump("g1-clean.xml") + suffix + "\n", raw)
        assertFalse(Fixtures.androidDump("g1-clean.xml").contains("dumped to"))
    }

    @Test
    fun `crlf sample uses CR LF for every line break`() {
        val bytes = Fixtures.bytes("android/g1-crlf.xml")
        val lineFeeds = bytes.indices.filter { bytes[it] == '\n'.code.toByte() }
        assertTrue(lineFeeds.size >= 4, "should span several lines")
        lineFeeds.forEach { assertEquals('\r'.code.toByte(), bytes[it - 1], "LF at $it is not preceded by CR") }
        assertTrue(Fixtures.androidDump("g1-crlf.xml").endsWith("$suffix\r\n"))
    }

    @Test
    fun `error samples start with ERROR and only the prefixed one has a tree`() {
        assertTrue(Fixtures.androidDump("error-prefixed.txt").startsWith("ERROR:"))
        assertTrue(Fixtures.androidDump("error-null-root.txt").startsWith("ERROR: null root node"))
        assertEquals(null, xmlSpan(Fixtures.androidDump("error-null-root.txt")))
    }

    @Test
    fun `list sample has the duplicate ids and the node without an id`() {
        val raw = Fixtures.androidDump("list-duplicate-ids.xml")
        val root = parse(xmlSpan(raw)!!)
        assertEquals(22, nodeCount(root))
        fun countAttr(value: String) = (0 until root.getElementsByTagName("node").length)
            .count { (root.getElementsByTagName("node").item(it) as Element).getAttribute("resource-id") == value }
        assertEquals(4, countAttr("com.example.shop:id/row_title"))
        assertEquals(4, countAttr("com.example.shop:id/order_row"))
        assertEquals(1, countAttr("com.example.shop:id/toolbar_title"))
        assertTrue(raw.contains("Café Müller") && raw.contains("Smith &amp; Sons"))
        assertTrue(raw.contains("drawing-order="), "list sample uses the API 30+ attribute set")
    }

    @Test
    fun `dialog sample has two window roots and the dialog overlaps the map view`() {
        val raw = Fixtures.androidDump("dialog-over-activity.xml")
        val root = parse(xmlSpan(raw)!!)
        val windows = directNodes(root)
        assertEquals(2, windows.size)
        assertEquals("com.example.maps", windows[0].getAttribute("package"))
        assertEquals("com.google.android.permissioncontroller", windows[1].getAttribute("package"))
        assertEquals("[90,820][990,1580]", windows[1].getAttribute("bounds"))
        assertTrue(raw.contains("text=\"Don&apos;t allow\""), "apostrophe must be escaped as in real dumps")
        assertTrue(raw.contains("resource-id=\"com.example.maps:id/map_view\""))
    }
}
