package com.titanicbhai.uiscope.testing

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/** XML parsing for tests with a hardened factory: no DTDs, no external entities (what an importer must also do). */
object SecureXml {
    fun parseDocument(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
            isExpandEntityReferences = false
            isNamespaceAware = false
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    fun parse(xml: String): Element = parseDocument(xml).documentElement

    /** Direct child elements named [tag]. */
    fun children(parent: Element, tag: String = "node"): List<Element> =
        (0 until parent.childNodes.length).map { parent.childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
}
