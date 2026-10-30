package com.titanicbhai.uiscope.export

import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.json.JsonReader
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.CaptureWarning
import com.titanicbhai.uiscope.model.DeviceInfo
import com.titanicbhai.uiscope.model.ElementFlags
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ScreenInfo
import com.titanicbhai.uiscope.model.Screenshot
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.model.WindowInfo
import com.titanicbhai.uiscope.testing.GoldenFiles
import com.titanicbhai.uiscope.testing.HostileStrings
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.n
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** G2 (JSON half), P1-2. */
class CaptureJsonTest {
    private fun roundTrip(c: Capture, includeSerial: Boolean = true): Capture =
        CaptureJson.decode(CaptureJson.encode(c, "1.0.0", includeSerial)).assertSuccess()

    private val every: Map<String, String> = HostileStrings.all + mapOf(
        "all-control-chars" to (0..0x1f).joinToString("") { it.toChar().toString() } + "\u007f\u0080\u2028\u2029\uFFFF",
        "json-lookalike" to "\",\"children\":[{\"x\":1}],\"a\":\"",
        "only-space" to "   ",
    )

    @Test
    fun `hostile text survives in every text field`() {
        for ((label, value) in every) {
            if (value.isEmpty()) continue
            val cap = TestTrees.capture(
                n(
                    "Cls$value", identifier = "id$value", text = value, description = value, name = value,
                    properties = mapOf("k$value" to value),
                ),
                id = "id-$value",
            ).copy(
                target = Target.Windows(WindowInfo(value, "proc$value.exe", 12L, "Fw$value", true, "0x1")),
                warnings = listOf(CaptureWarning("W", value)),
                screenshot = Screenshot("shot$value.png", 10, 20),
            )
            assertEquals(cap, roundTrip(cap), "round trip failed for $label")
        }
    }

    @Test
    fun `android target with device info round trips including the serial`() {
        val cap = TestTrees.capture(n("A", children = listOf(n("B"), n("C", children = listOf(n("D"))))), id = "abc-123")
            .copy(screen = ScreenInfo(1080, 2400, 0, 0, rotation = 3), screenshot = Screenshot("screenshot.png", 1080, 2400))
        assertEquals(cap, roundTrip(cap))
    }

    @Test
    fun `windows target keeps a negative origin and every flag`() {
        val flags = ElementFlags(
            enabled = false, clickable = true, longClickable = true, checkable = true, checked = true, focusable = true, focused = true,
            scrollable = true, selected = true, offscreen = true, password = true,
        )
        val cap = TestTrees.capture(
            listOf(n("Button", identifier = "ok", name = "OK", bounds = Bounds(-1900, 40, 120, 30), flags = flags)),
            target = Target.Windows(WindowInfo("Untitled - Notepad", "notepad.exe", 4242, "Win32", false, "0x00120A3C")),
            screen = ScreenInfo(1300, 900, -1920, 10),
        )
        assertEquals(cap, roundTrip(cap))
        assertEquals(false, roundTrip(cap).roots[0].flags.enabled)
    }

    @Test
    fun `a 5000 node tree round trips to an equal capture`() {
        val cap = TestTrees.capture(TestTrees.balanced(5000))
        val json = CaptureJson.encode(cap, "1.0.0", includeSerial = true)
        assertEquals(cap, CaptureJson.decode(json).assertSuccess())
        assertEquals(5000, ElementIndex(CaptureJson.decode(json).assertSuccess()).nodes.size)
    }

    @Test
    fun `a 5000 node tree is encoded and decoded quickly`() {
        val cap = TestTrees.capture(TestTrees.balanced(5000))
        CaptureJson.decode(CaptureJson.encode(cap, "1.0.0", true)).assertSuccess() // warm up
        val started = System.nanoTime()
        val json = CaptureJson.encode(cap, "1.0.0", true)
        CaptureJson.decode(json).assertSuccess()
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 1500, "encode + decode of 5000 nodes took $ms ms")
    }

    @Test
    fun `exports leave the serial out and local snapshots keep it`() {
        val cap = TestTrees.capture(n("A"), id = "x").copy(target = TestTrees.androidTarget("SERIAL-SECRET-12345"))
        assertFalse(CaptureJson.encode(cap, "1.0.0", includeSerial = false).contains("SERIAL-SECRET"))
        assertTrue(CaptureJson.encode(cap, "1.0.0", includeSerial = true).contains("SERIAL-SECRET-12345"))
        val back = roundTrip(cap, includeSerial = false).target as Target.Android
        assertEquals("", back.device.serial)
        assertEquals("Test phone", back.device.model)
    }

    @Test
    fun `no sentinel survives a redacted capture`() {
        val secrets = listOf("S-TEXT-1", "S-DESC-2", "S-NAME-3", "S-PROP-4", "S-TITLE-5")
        val cap = TestTrees.capture(
            listOf(n("A", text = secrets[0], description = secrets[1], name = secrets[2], properties = mapOf("HelpText" to secrets[3]))),
            target = Target.Windows(WindowInfo(secrets[4], "notepad.exe")),
        )
        val json = CaptureJson.encode(cap.redacted(), "1.0.0", includeSerial = false)
        secrets.forEach { assertFalse(json.contains(it), "$it survived redaction in the JSON export") }
    }

    @Test
    fun `output follows the shape of appendix D`() {
        val cap = TestTrees.capture(
            n("android.widget.FrameLayout", bounds = Bounds(0, 0, 1080, 2400), properties = mapOf("package" to "com.example.app", "index" to "0")),
            id = "capture-1",
        ).copy(screenshot = Screenshot("screenshot.png", 1080, 2400))
        @Suppress("UNCHECKED_CAST")
        val doc = JsonReader.parse(CaptureJson.encode(cap, "1.0.0", includeSerial = true)).assertSuccess() as Map<String, Any?>
        assertEquals(1L, doc["schemaVersion"])
        assertEquals(mapOf("name" to "UIScope", "version" to "1.0.0"), doc["app"])
        assertEquals("2026-01-01T00:00:00Z", doc["capturedAt"])
        assertEquals("screenshot.png", doc["screenshot"])
        @Suppress("UNCHECKED_CAST")
        val root = (doc["roots"] as List<Map<String, Any?>>)[0]
        assertEquals("0", root["path"])
        assertEquals(mapOf("x" to 0L, "y" to 0L, "width" to 1080L, "height" to 2400L), root["bounds"])
        assertEquals(emptyList<Any?>(), root["children"])
    }

    @Test
    fun `golden output for a small capture`() {
        val cap = TestTrees.capture(
            n(
                "android.widget.FrameLayout", bounds = Bounds(0, 0, 1080, 2400), properties = mapOf("package" to "com.example.app"),
                children = listOf(
                    n("android.widget.TextView", identifier = "com.example.app:id/title", text = "Say \"hi\"\n日本語", bounds = Bounds(10, 20, 300, 40)),
                    n("android.widget.Button", description = "Close", flags = ElementFlags(clickable = true, checkable = true, checked = false)),
                ),
            ),
            id = "capture-1",
        ).copy(
            screenshot = Screenshot("screenshot.png", 1080, 2400),
            warnings = listOf(CaptureWarning("SECURE_WINDOW", null)),
        )
        GoldenFiles.assertMatches("capture-json-android.json", CaptureJson.encode(cap, "1.0.0", includeSerial = false))
    }

    // ---------------------------------------------------------------- reading

    private fun minimal(extra: String = "", version: String = "1", roots: String = """[{"className":"A","bounds":{"x":0,"y":0,"width":1,"height":1}}]""") =
        """{"schemaVersion":$version,"capturedAt":"2026-01-01T00:00:00Z","target":{"type":"android","device":{}},"screen":{"width":10,"height":20},"roots":$roots$extra}"""

    @Test
    fun `a minimal document reads and defaults fill the rest`() {
        val c = CaptureJson.decode(minimal()).assertSuccess()
        assertEquals("imported", c.id)
        assertEquals(1, c.roots.size)
        assertEquals("0", c.roots[0].path.value)
        assertEquals(ElementFlags(), c.roots[0].flags)
        assertNull(c.screenshot)
        assertEquals(ScreenInfo(10, 20), c.screen)
    }

    @Test
    fun `a higher schema version is a clear typed error`() {
        val e = CaptureJson.decode(minimal(version = "2")).assertFailure(ErrorCodes.CAPTURE_SCHEMA_UNSUPPORTED)
        assertTrue(e.detail!!.contains("2") && e.detail!!.contains("1"), e.detail)
        assertTrue(e.detail!!.contains("Update UIScope"))
        CaptureJson.decode(minimal(version = "99")).assertFailure(ErrorCodes.CAPTURE_SCHEMA_UNSUPPORTED)
    }

    @Test
    fun `a missing or malformed schema version is a parse failure`() {
        for (v in listOf("0", "-1", "\"1\"", "1.5", "null", "true")) {
            CaptureJson.decode(minimal(version = v)).assertFailure(ErrorCodes.PARSE_FAILED)
        }
        CaptureJson.decode("""{"capturedAt":"2026-01-01T00:00:00Z"}""").assertFailure(ErrorCodes.PARSE_FAILED)
    }

    @Test
    fun `unknown keys are ignored at every level`() {
        val text = """{"schemaVersion":1,"future":{"a":[1,2]},"capturedAt":"2026-01-01T00:00:00Z","target":{"type":"android","extra":1,"device":{"serial":"S","x":1}},
            "screen":{"width":10,"height":20,"dpi":3},"roots":[{"className":"A","zzz":null,"bounds":{"x":0,"y":0,"width":1,"height":1,"z":9},"flags":{"future":true},"children":[]}]}"""
        assertEquals("S", ((CaptureJson.decode(text).assertSuccess().target as Target.Android).device.serial))
    }

    @Test
    fun `paths in the file are ignored and recomputed so duplicates cannot reach the index`() {
        val node = """{"path":"7/7","className":"A","bounds":{"x":0,"y":0,"width":1,"height":1}"""
        val c = CaptureJson.decode(minimal(roots = "[$node},$node,\"children\":[$node}]}]")).assertSuccess()
        val index = ElementIndex(c)
        assertEquals(listOf("0", "1", "1/0"), index.nodes.map { it.path.value })
    }

    @Test
    fun `blank strings read as null`() {
        val c = CaptureJson.decode(minimal(roots = """[{"className":"A","text":"","identifier":"","contentDescription":"","name":"","bounds":{"x":0,"y":0,"width":1,"height":1}}]""")).assertSuccess()
        val r = c.roots[0]
        assertNull(r.text)
        assertNull(r.identifier)
        assertNull(r.contentDescription)
        assertNull(r.name)
    }

    @Test
    fun `wrong shapes are typed errors that do not repeat the document`() {
        val secret = "SECRET-IN-DOCUMENT-77"
        val broken = listOf(
            """{"schemaVersion":1,"capturedAt":"$secret","target":{"type":"android","device":{}},"screen":{"width":1,"height":1},"roots":[]}""",
            """{"schemaVersion":1,"capturedAt":"2026-01-01T00:00:00Z","target":{"type":"$secret","device":{}},"screen":{"width":1,"height":1},"roots":[]}""",
            minimal(roots = """[{"className":"A","text":5,"bounds":{"x":0,"y":0,"width":1,"height":1},"properties":{"$secret":5}}]"""),
            minimal(roots = """[{"className":"A","bounds":{"x":"$secret","y":0,"width":1,"height":1}}]"""),
            minimal(roots = """[{"className":"A","bounds":{"x":0,"y":0,"width":1,"height":1},"children":"$secret"}]"""),
            minimal(roots = """[{"bounds":{"x":0,"y":0,"width":1,"height":1}}]"""),
            minimal(roots = """[{"className":"A"}]"""),
            minimal(roots = """[{"className":"A","bounds":{"x":99999999999,"y":0,"width":1,"height":1}}]"""),
            minimal(roots = "\"$secret\""),
            minimal(roots = """["$secret"]"""),
            "[\"$secret\"]",
            "\"$secret\"",
            "$secret",
            "",
        )
        for (text in broken) {
            val error = CaptureJson.decode(text).assertFailure(ErrorCodes.PARSE_FAILED)
            assertFalse((error.detail ?: "").contains(secret), "error repeated document text: ${error.detail}")
        }
    }

    @Test
    fun `nesting is limited and over the limit is a typed error`() {
        fun deep(levels: Int): String {
            val open = """{"className":"A","bounds":{"x":0,"y":0,"width":1,"height":1},"children":["""
            return minimal(roots = "[" + open.repeat(levels) + "]}".repeat(levels) + "]")
        }
        val ok = CaptureJson.decode(deep(CaptureJson.MAX_TREE_DEPTH)).assertSuccess()
        assertEquals(CaptureJson.MAX_TREE_DEPTH, ElementIndex(ok).nodes.size)
        CaptureJson.decode(deep(CaptureJson.MAX_TREE_DEPTH + 50)).assertFailure(ErrorCodes.PARSE_FAILED)
    }

    @Test
    fun `the loader never throws whatever it is given`() {
        val good = CaptureJson.encode(TestTrees.capture(TestTrees.balanced(40)), "1.0.0", true)
        // every truncation
        for (cut in 0 until good.length step 7) CaptureJson.decode(good.substring(0, cut)) // must return, not throw
        // random single-character damage
        val rnd = Random(12345)
        val alphabet = "{}[]\",:\\ntf0-9eE.\u0000\uD800"
        repeat(1500) {
            val chars = good.toCharArray()
            repeat(1 + rnd.nextInt(3)) { chars[rnd.nextInt(chars.size)] = alphabet[rnd.nextInt(alphabet.length)] }
            CaptureJson.decode(String(chars))
        }
        // random garbage
        repeat(200) { CaptureJson.decode(String(CharArray(rnd.nextInt(200)) { (rnd.nextInt(0x250)).toChar() })) }
    }
}
