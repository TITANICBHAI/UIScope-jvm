package com.titanicbhai.uiscope.export

import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.json.JsonReader
import com.titanicbhai.uiscope.json.JsonWriter
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.CaptureWarning
import com.titanicbhai.uiscope.model.DeviceInfo
import com.titanicbhai.uiscope.model.ElementFlags
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.model.ElementPaths
import com.titanicbhai.uiscope.model.ScreenInfo
import com.titanicbhai.uiscope.model.Screenshot
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.model.WindowInfo
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Capture JSON, schema v1 (plan Appendix D): what a snapshot stores and what the JSON export writes.
 *
 * Reading rules (G2): unknown keys are ignored at every level; a `schemaVersion` above [SCHEMA_VERSION] is a typed error
 * ([ErrorCodes.CAPTURE_SCHEMA_UNSUPPORTED]); anything else wrong is [ErrorCodes.PARSE_FAILED]. [decode] never throws, and its errors
 * never repeat text from the document (it may be a screen full of private data). Element paths in the file are **ignored**: they are
 * recomputed from the tree structure, so a hand-edited file can never produce duplicate paths.
 *
 * Keys beyond Appendix D (all optional, so older readers of v1 ignore them): `id`, `screen.rotation`, `screenshotWidth`,
 * `screenshotHeight`, the flags `longClickable`, `checkable`, `focusable`, `selected`, and `window.hwnd`.
 */
object CaptureJson {
    const val SCHEMA_VERSION = 1

    /** Deepest element nesting accepted when reading (the Android parser's own limit). */
    const val MAX_TREE_DEPTH = 400

    /** Largest element count accepted when reading. */
    const val MAX_NODES = 250_000

    /**
     * @param includeSerial Local snapshots keep the device serial; exports and `.uiscope` bundles omit it (Appendix D).
     * @param appVersion Written to `app.version`.
     */
    fun encode(capture: Capture, appVersion: String, includeSerial: Boolean): String {
        val out = StringBuilder(4096 + capture.roots.size * 256)
        out.append("{\n")
        out.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n")
        out.append("  \"app\": { \"name\": \"UIScope\", \"version\": ").append(q(appVersion)).append(" },\n")
        out.append("  \"id\": ").append(q(capture.id)).append(",\n")
        out.append("  \"capturedAt\": ").append(q(capture.takenAt.toString())).append(",\n")
        out.append("  \"target\": ").append(target(capture.target, includeSerial)).append(",\n")
        val s = capture.screen
        out.append("  \"screen\": { \"width\": ${s.width}, \"height\": ${s.height}, \"originX\": ${s.originX}, \"originY\": ${s.originY}, \"rotation\": ${s.rotation} },\n")
        val shot = capture.screenshot
        out.append("  \"screenshot\": ").append(if (shot == null) "null" else q(shot.file)).append(",\n")
        if (shot != null) out.append("  \"screenshotWidth\": ${shot.width},\n  \"screenshotHeight\": ${shot.height},\n")
        out.append("  \"warnings\": [")
        out.append(capture.warnings.joinToString(", ") { "{ \"code\": ${q(it.code)}, \"detail\": ${qn(it.detail)} }" })
        out.append("],\n")
        out.append("  \"roots\": [")
        capture.roots.forEachIndexed { i, root ->
            out.append(if (i == 0) "\n" else ",\n")
            node(out, root, 2)
        }
        out.append(if (capture.roots.isEmpty()) "]\n" else "\n  ]\n")
        out.append("}\n")
        return out.toString()
    }

    fun decode(text: String, fallbackId: String = "imported"): Outcome<Capture> {
        val parsed = when (val r = JsonReader.parse(text, maxDepth = 2 * MAX_TREE_DEPTH + 16)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return try {
            Outcome.Success(Reader(fallbackId).capture(parsed))
        } catch (e: Rejected) {
            Outcome.Failure(CodedError(e.code, e.reason))
        }
    }

    // ---------------------------------------------------------------- encoding

    private fun q(value: String) = JsonWriter.string(value)

    private fun qn(value: String?) = if (value == null) "null" else q(value)

    private fun target(t: Target, includeSerial: Boolean): String = when (t) {
        is Target.Android -> {
            val d = t.device
            val fields = buildList {
                if (includeSerial) add("\"serial\": ${q(d.serial)}")
                add("\"model\": ${qn(d.model)}")
                add("\"androidVersion\": ${qn(d.androidVersion)}")
                add("\"sdk\": ${d.sdkInt ?: "null"}")
            }
            "{ \"type\": \"android\", \"device\": { ${fields.joinToString(", ")} }, \"foregroundPackage\": ${qn(t.foregroundPackage)} }"
        }
        is Target.Windows -> {
            val w = t.window
            "{ \"type\": \"windows\", \"window\": { \"title\": ${q(w.title)}, \"processName\": ${q(w.processName)}, \"pid\": ${w.pid ?: "null"}, " +
                "\"frameworkId\": ${qn(w.frameworkId)}, \"isElevated\": ${w.isElevated}, \"hwnd\": ${qn(w.hwnd)} } }"
        }
    }

    private fun node(out: StringBuilder, n: ElementNode, level: Int) {
        val pad = "  ".repeat(minOf(level, 12))
        val f = n.flags
        out.append(pad).append("{ \"path\": ").append(q(n.path.value))
        out.append(", \"className\": ").append(q(n.className))
        out.append(", \"name\": ").append(qn(n.name))
        out.append(", \"identifier\": ").append(qn(n.identifier))
        out.append(", \"text\": ").append(qn(n.text))
        out.append(", \"contentDescription\": ").append(qn(n.contentDescription))
        out.append(", \"bounds\": { \"x\": ${n.bounds.x}, \"y\": ${n.bounds.y}, \"width\": ${n.bounds.width}, \"height\": ${n.bounds.height} }")
        out.append(", \"flags\": { \"enabled\": ${f.enabled}, \"clickable\": ${f.clickable}, \"longClickable\": ${f.longClickable}, ")
        out.append("\"checkable\": ${f.checkable}, \"checked\": ${f.checked ?: "null"}, \"focusable\": ${f.focusable}, \"focused\": ${f.focused}, ")
        out.append("\"scrollable\": ${f.scrollable}, \"selected\": ${f.selected}, \"offscreen\": ${f.offscreen}, \"password\": ${f.password} }")
        out.append(", \"properties\": {")
        out.append(n.properties.entries.joinToString(", ") { (k, v) -> "${q(k)}: ${q(v)}" })
        out.append("}, \"children\": [")
        if (n.children.isEmpty()) {
            out.append("] }")
        } else {
            n.children.forEachIndexed { i, c ->
                out.append(if (i == 0) "\n" else ",\n")
                node(out, c, level + 1)
            }
            out.append("\n").append(pad).append("] }")
        }
    }

    // ---------------------------------------------------------------- decoding

    private class Rejected(val code: String, val reason: String, cause: Throwable? = null) : Exception(reason, cause)

    private fun bad(reason: String): Nothing = throw Rejected(ErrorCodes.PARSE_FAILED, reason)

    private class Reader(private val fallbackId: String) {
        private var nodeCount = 0

        @Suppress("UNCHECKED_CAST")
        private fun obj(value: Any?, where: String): Map<String, Any?> = value as? Map<String, Any?> ?: bad("$where must be an object")

        @Suppress("UNCHECKED_CAST")
        private fun list(value: Any?, where: String): List<Any?> = value as? List<Any?> ?: bad("$where must be a list")

        private fun str(m: Map<String, Any?>, key: String, where: String): String? = when (val v = m[key]) {
            null -> null
            is String -> v
            else -> bad("$where.$key must be text")
        }

        private fun requiredStr(m: Map<String, Any?>, key: String, where: String): String =
            str(m, key, where) ?: bad("$where.$key is missing")

        private fun blankToNull(s: String?): String? = s?.takeIf { it.isNotEmpty() }

        private fun int(m: Map<String, Any?>, key: String, where: String): Int? = when (val v = m[key]) {
            null -> null
            is Long -> v.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: bad("$where.$key is out of range")
            else -> bad("$where.$key must be a whole number")
        }

        private fun requiredInt(m: Map<String, Any?>, key: String, where: String): Int = int(m, key, where) ?: bad("$where.$key is missing")

        private fun bool(m: Map<String, Any?>, key: String, default: Boolean): Boolean = when (val v = m[key]) {
            null -> default
            is Boolean -> v
            else -> bad("flag $key must be true or false")
        }

        fun capture(root: Any?): Capture {
            val doc = obj(root, "The document")
            when (val v = doc["schemaVersion"]) {
                null -> bad("schemaVersion is missing")
                is Long ->
                    if (v > SCHEMA_VERSION) {
                        throw Rejected(
                            ErrorCodes.CAPTURE_SCHEMA_UNSUPPORTED,
                            "This file uses capture format $v; this version of UIScope reads format $SCHEMA_VERSION and older. Update UIScope to open it.",
                        )
                    } else if (v < 1) {
                        bad("schemaVersion is invalid")
                    }
                else -> bad("schemaVersion must be a whole number")
            }
            val takenAt = try {
                Instant.parse(requiredStr(doc, "capturedAt", "The document"))
            } catch (e: DateTimeParseException) {
                throw Rejected(ErrorCodes.PARSE_FAILED, "capturedAt is not a valid UTC time", e)
            }
            val target = target(obj(doc["target"] ?: bad("target is missing"), "target"))
            val screen = screen(obj(doc["screen"] ?: bad("screen is missing"), "screen"))
            val shotName = blankToNull(str(doc, "screenshot", "The document"))
            val screenshot = shotName?.let {
                Screenshot(it, int(doc, "screenshotWidth", "The document") ?: screen.width, int(doc, "screenshotHeight", "The document") ?: screen.height)
            }
            val warnings = (doc["warnings"]?.let { list(it, "warnings") } ?: emptyList()).map {
                val w = obj(it, "A warning")
                CaptureWarning(requiredStr(w, "code", "A warning"), str(w, "detail", "A warning"))
            }
            val roots = list(doc["roots"] ?: bad("roots is missing"), "roots").map { node(obj(it, "A root element"), 0) }
            return Capture(
                id = blankToNull(str(doc, "id", "The document")) ?: fallbackId,
                takenAt = takenAt,
                target = target,
                screen = screen,
                screenshot = screenshot,
                roots = ElementPaths.assign(roots),
                warnings = warnings,
            )
        }

        private fun target(t: Map<String, Any?>): Target = when (val type = requiredStr(t, "type", "target")) {
            "android" -> {
                val d = obj(t["device"] ?: bad("target.device is missing"), "target.device")
                Target.Android(
                    DeviceInfo(
                        serial = str(d, "serial", "target.device") ?: "",
                        model = str(d, "model", "target.device"),
                        androidVersion = str(d, "androidVersion", "target.device"),
                        sdkInt = int(d, "sdk", "target.device"),
                    ),
                    blankToNull(str(t, "foregroundPackage", "target")),
                )
            }
            "windows" -> {
                val w = obj(t["window"] ?: bad("target.window is missing"), "target.window")
                val pid = when (val v = w["pid"]) {
                    null -> null
                    is Long -> v
                    else -> bad("target.window.pid must be a whole number")
                }
                Target.Windows(
                    WindowInfo(
                        title = str(w, "title", "target.window") ?: "",
                        processName = str(w, "processName", "target.window") ?: "",
                        pid = pid,
                        frameworkId = str(w, "frameworkId", "target.window"),
                        isElevated = bool(w, "isElevated", false),
                        hwnd = str(w, "hwnd", "target.window"),
                    ),
                )
            }
            else -> bad("target.type is not android or windows (it is a ${type.length}-character value)")
        }

        private fun screen(s: Map<String, Any?>) = ScreenInfo(
            width = requiredInt(s, "width", "screen"),
            height = requiredInt(s, "height", "screen"),
            originX = int(s, "originX", "screen") ?: 0,
            originY = int(s, "originY", "screen") ?: 0,
            rotation = int(s, "rotation", "screen") ?: 0,
        )

        private fun node(n: Map<String, Any?>, depth: Int): ElementNode {
            if (depth > MAX_TREE_DEPTH) bad("The tree is nested deeper than $MAX_TREE_DEPTH levels")
            if (++nodeCount > MAX_NODES) bad("The tree has more than $MAX_NODES elements")
            val b = obj(n["bounds"] ?: bad("An element has no bounds"), "bounds")
            val f = (n["flags"]?.let { obj(it, "flags") }) ?: emptyMap()
            val props = (n["properties"]?.let { obj(it, "properties") }) ?: emptyMap()
            val children = (n["children"]?.let { list(it, "children") }) ?: emptyList()
            return ElementNode(
                path = ElementPath.root(),
                name = blankToNull(str(n, "name", "An element")),
                className = requiredStr(n, "className", "An element"),
                identifier = blankToNull(str(n, "identifier", "An element")),
                text = blankToNull(str(n, "text", "An element")),
                contentDescription = blankToNull(str(n, "contentDescription", "An element")),
                bounds = Bounds(requiredInt(b, "x", "bounds"), requiredInt(b, "y", "bounds"), requiredInt(b, "width", "bounds"), requiredInt(b, "height", "bounds")),
                flags = ElementFlags(
                    enabled = bool(f, "enabled", true),
                    clickable = bool(f, "clickable", false),
                    longClickable = bool(f, "longClickable", false),
                    checkable = bool(f, "checkable", false),
                    checked = when (val v = f["checked"]) {
                        null -> null
                        is Boolean -> v
                        else -> bad("flag checked must be true, false or null")
                    },
                    focusable = bool(f, "focusable", false),
                    focused = bool(f, "focused", false),
                    scrollable = bool(f, "scrollable", false),
                    selected = bool(f, "selected", false),
                    offscreen = bool(f, "offscreen", false),
                    password = bool(f, "password", false),
                ),
                properties = props.mapValues { (_, v) -> v as? String ?: bad("a property value must be text") },
                children = children.map { node(obj(it, "An element"), depth + 1) },
            )
        }
    }
}
