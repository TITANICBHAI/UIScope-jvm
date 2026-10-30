package com.titanicbhai.uiscope.snapshot

import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.Screenshot
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.testing.HostileStrings
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.n
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P5-1 import validation (schemaVersion, size, traversal, missing capture) and P7-3 ZIP-slip guard, core half. */
class BundleCodecTest {
    private val now = Instant.parse("2026-02-03T04:05:06Z")

    /** A minimal valid PNG header (signature + IHDR for the given size); enough for [BundleCodec.pngSize]. */
    private fun png(w: Int, h: Int, extra: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(byteArrayOf(0, 0, 0, 13))
        out.write("IHDR".toByteArray())
        for (v in listOf(w, h)) out.write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
        out.write(byteArrayOf(8, 6, 0, 0, 0))
        out.write(ByteArray(4)) // crc (not checked)
        out.write(ByteArray(extra))
        return out.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>, build: (ZipOutputStream) -> Unit = {}): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
            build(z)
        }
        return out.toByteArray()
    }

    private val goodCapture: String
        get() = com.titanicbhai.uiscope.export.CaptureJson.encode(TestTrees.capture(n("A")), "1.0.0", false)

    private fun read(bytes: ByteArray) = BundleCodec.read(ByteArrayInputStream(bytes))

    @Test
    fun `a bundle round trips tree screenshot and manifest and never carries the serial`() {
        val cap = TestTrees.capture(
            listOf(n("A", text = HostileStrings.common.values.joinToString("|"), children = listOf(n("B"), n("C")))),
            target = TestTrees.androidTarget("SERIAL-SECRET-1234"),
        ).copy(screenshot = Screenshot("/tmp/live.png", 1080, 2400))
        val out = ByteArrayOutputStream()
        BundleCodec.write(out, cap, png(1080, 2400), "1.0.0", now, "A title", "A note").assertSuccess()
        assertFalse(String(out.toByteArray(), Charsets.ISO_8859_1).contains("SERIAL-SECRET"), "the serial is in the bundle")
        val bundle = read(out.toByteArray()).assertSuccess()
        assertEquals(cap.roots, bundle.capture.roots)
        assertEquals("", (bundle.capture.target as Target.Android).device.serial)
        assertEquals(Screenshot("screenshot.png", 1080, 2400), bundle.capture.screenshot)
        assertContentEquals(png(1080, 2400), bundle.screenshotPng)
        assertEquals(BundleManifest(1, "1.0.0", now, "A title", "A note"), bundle.manifest)
        assertEquals(3, ElementIndex(bundle.capture).nodes.size)
    }

    @Test
    fun `a bundle without a screenshot is fine`() {
        val out = ByteArrayOutputStream()
        BundleCodec.write(out, TestTrees.capture(n("A")), null, "1.0.0", now).assertSuccess()
        val bundle = read(out.toByteArray()).assertSuccess()
        assertNull(bundle.screenshotPng)
        assertNull(bundle.capture.screenshot)
    }

    @Test
    fun `a missing capture json is refused`() {
        read(zip("manifest.json" to "{}".toByteArray(), "screenshot.png" to png(1, 1))).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
        read(zip()).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
    }

    @Test
    fun `a wrong or newer schema version is refused with a reason`() {
        val newer = goodCapture.replace("\"schemaVersion\": 1", "\"schemaVersion\": 7")
        val e = read(zip("capture.json" to newer.toByteArray())).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
        assertTrue(e.detail!!.contains("newer") || e.detail!!.contains("format 7"), e.detail)
        read(zip("capture.json" to goodCapture.replace("\"schemaVersion\": 1", "\"schemaVersion\": \"x\"").toByteArray())).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
        read(zip("capture.json" to "not json".toByteArray())).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
        read(zip("capture.json" to ByteArray(0))).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
    }

    @Test
    fun `path traversal entries are refused whatever they contain`() {
        val bad = listOf("../evil.txt", "..\\evil.txt", "a/../../evil", "/etc/passwd", "\\\\server\\share\\x", "C:\\Windows\\x.dll", "C:/x", "ok/../../x", "x\u0000.png")
        for (name in bad) {
            val e = read(zip("capture.json" to goodCapture.toByteArray(), name to "x".toByteArray())).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
            assertFalse(e.detail!!.contains("evil"), "the entry name was repeated in the error")
        }
        // A traversal name in place of a known entry is refused too, never mapped to it.
        read(zip("../capture.json" to goodCapture.toByteArray())).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
    }

    @Test
    fun `unknown harmless entries are ignored and never extracted`() {
        val bundle = read(zip("capture.json" to goodCapture.toByteArray(), "README.txt" to "hi".toByteArray(), "sub/" to ByteArray(0))).assertSuccess()
        assertNotNull(bundle.capture)
    }

    @Test
    fun `duplicate entries are refused`() {
        // ZipOutputStream refuses duplicates, so write two names and patch one to equal the other.
        val raw = zip("capture.jsox" to goodCapture.toByteArray(), "capture.json" to goodCapture.toByteArray())
        val marker = "capture.jsox".toByteArray()
        val dup = raw.copyOf()
        for (i in 0..dup.size - marker.size) if ((marker.indices).all { dup[i + it] == marker[it] }) dup[i + marker.size - 1] = 'n'.code.toByte()
        read(dup).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
    }

    @Test
    fun `too many entries are refused`() {
        val many = zip { z -> repeat(BundleCodec.MAX_ENTRIES + 1) { z.putNextEntry(ZipEntry("f$it.txt")); z.closeEntry() } }
        read(many).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
    }

    @Test
    fun `a zip bomb is stopped by the unpacked size cap without unpacking it`() {
        // 150 MB of zeros compresses to ~150 KB; the cap counts inflated bytes.
        val bomb = ByteArrayOutputStream()
        ZipOutputStream(bomb).use { z ->
            z.putNextEntry(ZipEntry("capture.json"))
            val chunk = ByteArray(1 shl 20)
            repeat(150) { z.write(chunk) }
            z.closeEntry()
        }
        assertTrue(bomb.size() < 5_000_000, "test setup: the bomb should be small (was ${bomb.size()})")
        val started = System.nanoTime()
        val e = read(bomb.toByteArray()).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
        assertTrue(e.detail!!.contains("100 MB"), e.detail)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "took too long to refuse")
        // Same for an unknown entry that is never extracted.
        val bomb2 = ByteArrayOutputStream()
        ZipOutputStream(bomb2).use { z ->
            z.putNextEntry(ZipEntry("padding.bin"))
            val chunk = ByteArray(1 shl 20)
            repeat(150) { z.write(chunk) }
            z.closeEntry()
        }
        read(bomb2.toByteArray()).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
    }

    @Test
    fun `a file bigger than the cap on disk is refused while reading`() {
        val big = object : java.io.InputStream() {
            var served = 0L
            override fun read(): Int = if (served++ < BundleCodec.MAX_BYTES + 10) 0x50 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= BundleCodec.MAX_BYTES + 10) return -1
                val n = minOf(len.toLong(), BundleCodec.MAX_BYTES + 10 - served).toInt()
                java.util.Arrays.fill(b, off, off + n, 0x50)
                served += n
                return n
            }
        }
        BundleCodec.read(big).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
    }

    @Test
    fun `garbage and truncated archives never throw`() {
        read(ByteArray(0)).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
        read("this is not a zip file at all".toByteArray()).assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
        val good = ByteArrayOutputStream().also { BundleCodec.write(it, TestTrees.capture(n("A")), png(2, 2), "1.0.0", now).assertSuccess() }.toByteArray()
        for (cut in good.indices step 5) read(good.copyOf(cut)) // returns, does not throw
        val rnd = java.util.Random(7)
        repeat(300) {
            val damaged = good.copyOf()
            repeat(1 + rnd.nextInt(4)) { damaged[rnd.nextInt(damaged.size)] = rnd.nextInt(256).toByte() }
            read(damaged)
        }
    }

    @Test
    fun `an unusable screenshot is dropped with a warning and the tree is kept`() {
        val bundle = read(zip("capture.json" to goodCapture.toByteArray(), "screenshot.png" to "GIF89a not a png".toByteArray())).assertSuccess()
        assertNull(bundle.screenshotPng)
        assertNull(bundle.capture.screenshot)
        assertTrue(bundle.capture.warnings.any { it.code == ErrorCodes.SCREENSHOT_FAILED })
        // A PNG header with an absurd size is not trusted either.
        assertNull(BundleCodec.pngSize(png(0, 10)))
        assertNull(BundleCodec.pngSize(png(100_000, 10)))
        assertEquals(1080 to 2400, BundleCodec.pngSize(png(1080, 2400)))
        assertNull(BundleCodec.pngSize(ByteArray(10)))
    }

    @Test
    fun `the manifest is optional and a damaged one is tolerated`() {
        val bundle = read(zip("capture.json" to goodCapture.toByteArray(), "manifest.json" to "{{{".toByteArray())).assertSuccess()
        assertEquals("unknown", bundle.manifest.appVersion)
        assertEquals(Instant.EPOCH, bundle.manifest.createdAt)
    }

    @Test
    fun `writing to a failing stream is a typed error`() {
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw java.io.IOException("disk gone")
        }
        BundleCodec.write(broken, TestTrees.capture(n("A")), null, "1.0.0", now).assertFailure(ErrorCodes.EXPORT_FAILED)
    }
}
