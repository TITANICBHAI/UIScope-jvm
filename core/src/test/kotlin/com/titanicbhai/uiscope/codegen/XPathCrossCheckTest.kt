package com.titanicbhai.uiscope.codegen

import com.titanicbhai.uiscope.export.XmlExporter
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.selector.Selector
import com.titanicbhai.uiscope.selector.SelectorEngine
import com.titanicbhai.uiscope.selector.SelectorEvaluator
import com.titanicbhai.uiscope.selector.SelectorKey
import com.titanicbhai.uiscope.testing.HostileStrings
import com.titanicbhai.uiscope.testing.SecureXml
import com.titanicbhai.uiscope.testing.TestTrees.capture
import com.titanicbhai.uiscope.testing.TestTrees.n
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The generated XPath is evaluated three independent ways and all three must agree: the selector evaluator, the small XPath
 * evaluator for our subset, and `javax.xml.xpath` over the exported XML.
 */
class XPathCrossCheckTest {
    private fun domPath(element: Element, root: Element): String {
        val parts = ArrayList<Int>()
        var current: Node = element
        while (current !== root) {
            val parent = current.parentNode
            var position = 0
            var sibling = parent.firstChild
            while (sibling !== current) {
                if (sibling is Element) position++
                sibling = sibling.nextSibling
            }
            parts += position
            current = parent
        }
        return parts.asReversed().joinToString("/")
    }

    private fun javaxPaths(xml: String, expression: String): List<String> {
        val doc = SecureXml.parseDocument(xml)
        val nodes = XPathFactory.newInstance().newXPath().compile(expression).evaluate(doc, XPathConstants.NODESET) as NodeList
        return (0 until nodes.length).map { domPath(nodes.item(it) as Element, doc.documentElement) }
    }

    private val tree = capture(
        listOf(
            n(
                "android.widget.FrameLayout", identifier = "app:id/root", bounds = Bounds(0, 0, 1080, 2400),
                children = listOf(
                    n("android.widget.Button", text = HostileStrings.common.getValue("apostrophe"), bounds = Bounds(0, 0, 100, 50)),
                    n("android.widget.Button", text = HostileStrings.common.getValue("both-quotes"), identifier = "app:id/both", bounds = Bounds(0, 60, 100, 50)),
                    n("android.widget.TextView", text = HostileStrings.common.getValue("newline"), bounds = Bounds(0, 120, 100, 50)),
                    n("android.widget.TextView", text = HostileStrings.common.getValue("markup"), description = HostileStrings.common.getValue("double-quote"), bounds = Bounds(0, 180, 100, 50)),
                    n("android.widget.TextView", text = HostileStrings.common.getValue("cjk"), bounds = Bounds(0, 240, 100, 50)),
                    n(
                        "android.view.ViewGroup", identifier = "app:id/list", bounds = Bounds(0, 300, 100, 300),
                        children = (0 until 3).map { r -> n("android.widget.TextView", identifier = "app:id/row", bounds = Bounds(0, 300 + r * 100, 100, 90)) },
                    ),
                    n("android.view.View", bounds = Bounds(0, 700, 10, 10)),
                ),
            ),
        ),
    )

    @Test
    fun `selector evaluator, XPath subset evaluator and javax xpath agree for every candidate of every element`() {
        val index = ElementIndex(tree)
        val xml = XmlExporter.export(tree)
        var compared = 0
        for (node in index.nodes) {
            for (candidate in SelectorEngine.suggest(index, node).candidates) {
                val expression = XPath.render(candidate.selector) ?: continue
                val expected = SelectorEvaluator.evaluate(index, candidate.selector).map { it.path.value }
                val subset = XPathSubset.evaluate(index, expression)
                assertNotNull(subset, "subset evaluator could not read $expression")
                assertEquals(expected, subset.map { it.path.value }, "subset evaluator, ${candidate.expression}: $expression")
                assertEquals(expected, javaxPaths(xml, expression), "javax.xml.xpath, ${candidate.expression}: $expression")
                compared++
            }
        }
        assertTrue(compared > 40, "only $compared expressions compared")
    }

    @Test
    fun `every hostile text selects exactly its element through real XPath`() {
        for ((name, text) in HostileStrings.common.filterValues { it.isNotEmpty() }) {
            val cap = capture(n("android.widget.FrameLayout", children = listOf(n(text = "other"), n(text = text), n(text = text + "!"))))
            val expression = XPath.render(Selector.Attr(SelectorKey.TEXT, text))!!
            assertEquals(listOf("0/1"), javaxPaths(XmlExporter.export(cap), expression), "$name: $expression")
            assertEquals(listOf("0/1"), XPathSubset.evaluate(ElementIndex(cap), expression)?.map { it.path.value }, "$name (subset)")
        }
    }

    @Test
    fun `rendered shapes`() {
        val id = Selector.Attr(SelectorKey.IDENTIFIER, "a:id/x")
        assertEquals("//*[@resource-id='a:id/x']", XPath.render(id))
        assertEquals("(//*[@resource-id='a:id/x'])[4]", XPath.render(Selector.Index(id, 3)))
        assertEquals("//*[@resource-id='a:id/x']/*[3]/*[1]", XPath.render(Selector.Anchor(id, listOf(2, 0))))
        assertEquals("/hierarchy/*[1]/*[4]/*[2]", XPath.render(Selector.Path(ElementPath("0/3/1"))))
        assertEquals(null, XPath.render(Selector.Coordinates(1, 2)))
        assertEquals(null, XPath.render(Selector.Attr(SelectorKey.NAME, "windows only")))
        assertEquals(null, XPathSubset.evaluate(ElementIndex(tree), "//node[@text='x']"), "tags are outside the subset")
        val outcome = XPathSubset.tryEvaluate(ElementIndex(tree), "//node[@text='x']")
        assertTrue(outcome is XPathSubset.Outcome.Unsupported && outcome.reason.isNotBlank(), outcome.toString())
    }
}
