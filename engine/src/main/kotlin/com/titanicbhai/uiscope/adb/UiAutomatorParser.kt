package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.error.Remedy
import com.titanicbhai.uiscope.export.XmlExporter
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.CaptureWarning
import com.titanicbhai.uiscope.model.ElementFlags
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.model.ElementPaths
import com.titanicbhai.uiscope.model.ScreenInfo
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.w3c.dom.Element
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

data class ParseResult(
    /** Never empty: an unreadable or empty dump is a failure, not an empty list (B1, M16). */
    val roots: List<ElementNode>,
    val screen: ScreenInfo,
    val warnings: List<CaptureWarning>,
)

/**
 * Parses the output of `uiautomator dump` into element trees.
 *
 * - The XML is sliced from the first `<?xml` (or `<hierarchy`) to the **last** `</hierarchy>`; everything outside that span (the
 *   `UI hierchary dumped to:` suffix, `ERROR:` lines, a BOM, CR LF noise) is ignored, or classified into an Appendix A code when there is no XML.
 * - Characters XML cannot hold are removed first; the parser is hardened (no DOCTYPE, no external entities, secure processing).
 * - All attributes are kept: the standard ones as fields/flags, `package`, `index`, `hint`, `drawing-order`, `display-id` as properties.
 */
object UiAutomatorParser {
    private val boundsPattern = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
    private const val MAX_DEPTH = 400
    private const val MAX_INPUT_CHARS = 64 * 1024 * 1024
    private val extraProperties = listOf("package", "index", "hint", "drawing-order", "display-id")

    fun parse(raw: String): Outcome<ParseResult> {
        if (raw.length > MAX_INPUT_CHARS) return failure(ErrorCodes.PARSE_FAILED, "The dump is larger than ${MAX_INPUT_CHARS / (1024 * 1024)} MB")
        val hierarchyStart = raw.indexOf("<hierarchy")
        if (hierarchyStart < 0) return noHierarchy(raw)
        val end = raw.lastIndexOf("</hierarchy>")
        if (end < 0) return failure(ErrorCodes.PARSE_FAILED, "The dump is cut off: no closing hierarchy tag")
        val declaration = raw.lastIndexOf("<?xml", hierarchyStart)
        val start = if (declaration >= 0) declaration else hierarchyStart
        val xml = XmlExporter.stripInvalidXmlChars(raw.substring(start, end + "</hierarchy>".length))
        return parseXml(xml)
    }

    private fun parseXml(xml: String): Outcome<ParseResult> {
        val root = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement
        } catch (e: SAXParseException) {
            return failure(ErrorCodes.PARSE_FAILED, "The dump is not valid XML (line ${e.lineNumber}, column ${e.columnNumber})")
        } catch (e: SAXException) {
            return failure(ErrorCodes.PARSE_FAILED, "The dump is not valid XML (${e.javaClass.simpleName})")
        } catch (e: ParserConfigurationException) {
            return failure(ErrorCodes.PARSE_FAILED, "The XML parser could not be configured (${e.javaClass.simpleName})")
        } catch (e: IOException) {
            return failure(ErrorCodes.PARSE_FAILED, "The dump could not be read (${e.javaClass.simpleName})")
        }
        val rotation = root.getAttribute("rotation").toIntOrNull() ?: 0
        val roots = ArrayList<ElementNode>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && child.tagName == "node") {
                roots += convert(child, 0) ?: return failure(ErrorCodes.PARSE_FAILED, "The tree is nested deeper than $MAX_DEPTH levels")
            }
        }
        if (roots.isEmpty()) return failure(ErrorCodes.PARSE_FAILED, "The dump contains no elements")
        val assigned = ElementPaths.assign(roots)
        return Outcome.Success(ParseResult(assigned, screenOf(assigned, rotation), warningsOf(assigned)))
    }

    private fun convert(el: Element, depth: Int): ElementNode? {
        if (depth > MAX_DEPTH) return null
        val kids = ArrayList<ElementNode>()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val child = nodes.item(i)
            if (child is Element && child.tagName == "node") kids += convert(child, depth + 1) ?: return null
        }
        fun flag(name: String) = el.getAttribute(name) == "true"
        val checkable = flag("checkable")
        val checked = flag("checked")
        val properties = LinkedHashMap<String, String>()
        for (key in extraProperties) if (el.hasAttribute(key) && (key != "hint" || el.getAttribute(key).isNotEmpty())) properties[key] = el.getAttribute(key)
        return ElementNode(
            path = ElementPath.root(),
            className = el.getAttribute("class"),
            identifier = el.getAttribute("resource-id").ifEmpty { null },
            text = el.getAttribute("text").ifEmpty { null },
            contentDescription = el.getAttribute("content-desc").ifEmpty { null },
            bounds = parseBounds(el.getAttribute("bounds")),
            flags = ElementFlags(
                enabled = el.getAttribute("enabled") != "false",
                clickable = flag("clickable"),
                longClickable = flag("long-clickable"),
                checkable = checkable,
                checked = if (checkable) checked else if (checked) true else null,
                focusable = flag("focusable"),
                focused = flag("focused"),
                scrollable = flag("scrollable"),
                selected = flag("selected"),
                password = flag("password"),
            ),
            properties = properties,
            children = kids,
        )
    }

    private fun parseBounds(text: String): Bounds {
        val m = boundsPattern.matchEntire(text.trim()) ?: return Bounds.EMPTY
        val (l, t, r, b) = m.destructured
        return Bounds.fromEdges(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    private fun screenOf(roots: List<ElementNode>, rotation: Int): ScreenInfo {
        val width = roots.maxOf { it.bounds.right }.coerceAtLeast(0)
        val height = roots.maxOf { it.bounds.bottom }.coerceAtLeast(0)
        return ScreenInfo(width, height, 0, 0, rotation)
    }

    /** `SECURE_WINDOW` when the whole tree is system UI or the keyguard (a locked screen or a window that hides its content). */
    private fun warningsOf(roots: List<ElementNode>): List<CaptureWarning> {
        val nodes = ArrayList<ElementNode>()
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val n = pending.removeLast()
            nodes += n
            pending.addAll(n.children)
        }
        val system = nodes.count { isSystemPackage(it.properties["package"]) }
        return if (system == nodes.size) {
            listOf(CaptureWarning(ErrorCodes.SECURE_WINDOW, "Only system UI or keyguard elements were found"))
        } else {
            emptyList()
        }
    }

    private fun isSystemPackage(pkg: String?) = pkg != null && (pkg.startsWith("com.android.systemui") || pkg.contains("keyguard"))

    /** No XML at all: adb or uiautomator printed an error instead. */
    private fun noHierarchy(raw: String): Outcome<ParseResult> {
        val text = raw.lowercase()
        val code = when {
            "null root node" in text -> ErrorCodes.DUMP_NULL_ROOT
            "could not get idle state" in text -> ErrorCodes.DUMP_IDLE_TIMEOUT
            "uiautomation not connected" in text || "already registered" in text || "already connected" in text -> ErrorCodes.UIAUTOMATOR_BUSY
            else -> ErrorCodes.PARSE_FAILED
        }
        val detail = if (code == ErrorCodes.PARSE_FAILED) "The dump contains no UI hierarchy (${raw.length} characters of output)" else AdbErrors.firstLine(raw)
        return failure(code, detail)
    }

    private fun failure(code: String, detail: String) = Outcome.Failure(CodedError(code, detail, Remedy.RETRY))
}
