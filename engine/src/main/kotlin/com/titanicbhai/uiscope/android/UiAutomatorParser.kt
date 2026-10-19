package com.titanicbhai.uiscope.android

import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementNode
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class UiAutomatorParser {

    private val boundsRegex = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
    private val obfuscatedIdRegex = Regex("""0x[0-9a-fA-F]+""")

    fun parse(xml: String): List<ElementNode> {
        val cleaned = xml.substringAfter("<?xml").let {
            if (it == xml) xml else "<?xml$it"
        }.trim()
        if (cleaned.isBlank()) return emptyList()
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(cleaned)))
            val root = doc.documentElement
            val topLevelNodes = mutableListOf<Element>()
            val children = root.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child is Element && child.tagName == "node") {
                    topLevelNodes.add(child)
                }
            }
            topLevelNodes.mapIndexed { idx, el ->
                parseNode(el, depth = 0, siblingIndex = idx)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseNode(element: Element, depth: Int, siblingIndex: Int): ElementNode {
        val className = element.getAttribute("class").takeIf { it.isNotBlank() } ?: "View"
        val resourceId = element.getAttribute("resource-id").takeIf { it.isNotBlank() }
        val text = element.getAttribute("text").takeIf { it.isNotBlank() }
        val contentDesc = element.getAttribute("content-desc").takeIf { it.isNotBlank() }
        val pkg = element.getAttribute("package").takeIf { it.isNotBlank() }
        val boundsStr = element.getAttribute("bounds")
        val bounds = parseBounds(boundsStr)

        val childEls = mutableListOf<Element>()
        val childNodes = element.childNodes
        for (i in 0 until childNodes.length) {
            val child = childNodes.item(i)
            if (child is Element && child.tagName == "node") childEls.add(child)
        }

        val children = childEls.mapIndexed { idx, el ->
            parseNode(el, depth + 1, idx)
        }

        val name = when {
            !text.isNullOrBlank() -> text
            !contentDesc.isNullOrBlank() -> contentDesc
            !resourceId.isNullOrBlank() -> resourceId.substringAfterLast('/')
            else -> className.substringAfterLast('.')
        }

        val nodeId = "${pkg ?: ""}/${className}/${boundsStr}/${depth}/${siblingIndex}"

        return ElementNode(
            id = nodeId,
            name = name,
            className = className,
            resourceId = resourceId,
            text = text,
            contentDescription = contentDesc,
            bounds = bounds,
            isEnabled = element.getAttribute("enabled") != "false",
            isClickable = element.getAttribute("clickable") == "true",
            isScrollable = element.getAttribute("scrollable") == "true",
            isFocused = element.getAttribute("focused") == "true",
            isChecked = element.getAttribute("checkable")
                .takeIf { it == "true" }
                ?.let { element.getAttribute("checked") == "true" },
            packageName = pkg,
            depth = depth,
            siblingIndex = siblingIndex,
            children = children,
            properties = buildMap {
                put("index", element.getAttribute("index"))
                put("long-clickable", element.getAttribute("long-clickable"))
                put("password", element.getAttribute("password"))
                put("selected", element.getAttribute("selected"))
                put("rotation", element.getAttribute("rotation").takeIf { it.isNotBlank() })
            }
        )
    }

    private fun parseBounds(boundsStr: String): Bounds? {
        val match = boundsRegex.find(boundsStr) ?: return null
        val (x1, y1, x2, y2) = match.destructured
        return Bounds(
            x = x1.toInt(),
            y = y1.toInt(),
            width = x2.toInt() - x1.toInt(),
            height = y2.toInt() - y1.toInt()
        )
    }

    fun isObfuscated(resourceId: String?): Boolean {
        if (resourceId.isNullOrBlank()) return false
        return obfuscatedIdRegex.containsMatchIn(resourceId)
    }
}
