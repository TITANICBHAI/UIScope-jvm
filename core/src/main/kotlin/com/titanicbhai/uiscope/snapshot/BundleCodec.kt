package com.titanicbhai.uiscope.snapshot

import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.export.CaptureJson
import com.titanicbhai.uiscope.json.JsonReader
import com.titanicbhai.uiscope.json.JsonWriter
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.CaptureWarning
import com.titanicbhai.uiscope.model.Screenshot
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What a `.uiscope` bundle says about itself (`manifest.json`). */
data class BundleManifest(val bundleVersion: Int, val appVersion: String, val createdAt: Instant, val title: String?, val note: String?)

/** A validated, fully decoded bundle. [screenshotPng] is null when the bundle has none or its image was unusable. */
class Bundle(val capture: Capture, val screenshotPng: ByteArray?, val manifest: BundleManifest)

/**
 * `.uiscope` files: a zip of `manifest.json`, `capture.json` and optionally `screenshot.png` (plan §4.7).
 *
 * Reading is defensive (P5-1, P7-3). Nothing in the archive is ever extracted by name: the three known entries are read into memory
 * and everything else is ignored, so a ZIP-slip entry cannot write anywhere, but an archive that *contains* a traversal-style name is
 * refused anyway because it is not something UIScope wrote. Caps: [MAX_BYTES] compressed and [MAX_BYTES] in total after inflating,
 * at most [MAX_ENTRIES] entries, no duplicate entries. Error texts never repeat names or content from the file.
 *
 * Bundles written here never contain the device serial.
 */
object BundleCodec {
    const val MANIFEST = "manifest.json"
    const val CAPTURE = "capture.json"
    const val SCREENSHOT = "screenshot.png"
    const val BUNDLE_VERSION = 1
    const val MAX_BYTES = 100L * 1024 * 1024
    const val MAX_ENTRIES = 32
    private const val CHUNK = 64 * 1024
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Writes a bundle to [out] (not closed). [screenshotPng] is stored as is. */
    fun write(
        out: OutputStream,
        capture: Capture,
        screenshotPng: ByteArray?,
        appVersion: String,
        createdAt: Instant,
        title: String? = null,
        note: String? = null,
    ): Outcome<Unit> {
        val stored = capture.copy(
            screenshot = if (screenshotPng != null) Screenshot(SCREENSHOT, capture.screenshot?.width ?: 0, capture.screenshot?.height ?: 0) else null,
        )
        val captureJson = CaptureJson.encode(stored, appVersion, includeSerial = false).toByteArray(Charsets.UTF_8)
        val manifest = buildString {
            append("{\n  \"format\": \"uiscope-bundle\",\n  \"bundleVersion\": $BUNDLE_VERSION,\n")
            append("  \"app\": { \"name\": \"UIScope\", \"version\": ${JsonWriter.string(appVersion)} },\n")
            append("  \"createdAt\": ${JsonWriter.string(createdAt.toString())},\n")
            append("  \"title\": ${title?.let { JsonWriter.string(it) } ?: "null"},\n")
            append("  \"note\": ${note?.let { JsonWriter.string(it) } ?: "null"}\n}\n")
        }.toByteArray(Charsets.UTF_8)
        return try {
            val zip = ZipOutputStream(out, Charsets.UTF_8)
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name).also { it.time = createdAt.toEpochMilli() })
                zip.write(bytes)
                zip.closeEntry()
            }
            entry(MANIFEST, manifest)
            entry(CAPTURE, captureJson)
            if (screenshotPng != null) entry(SCREENSHOT, screenshotPng)
            zip.finish()
            Outcome.Success(Unit)
        } catch (e: IOException) {
            Outcome.Failure(CodedError(ErrorCodes.EXPORT_FAILED, "Could not write the bundle (${e.javaClass.simpleName})."))
        }
    }

    /** Reads and validates a bundle from [input] (not closed). Never throws. */
    fun read(input: InputStream): Outcome<Bundle> {
        val entries = HashMap<String, ByteArray>()
        var total = 0L
        var count = 0
        try {
            val zip = ZipInputStream(BoundedInput(input, MAX_BYTES), Charsets.UTF_8)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) return invalid("It contains too many files.")
                val name = entry.name
                if (!safeName(name)) return invalid("It contains a file name that points outside the bundle, so it was not made by UIScope.")
                if (entry.isDirectory) continue
                if (name !in KNOWN) {
                    // Never extracted; read only to move past it, still counted against the cap.
                    total += drain(zip, MAX_BYTES - total) ?: return invalid("It is larger than ${MAX_BYTES / (1024 * 1024)} MB once unpacked.")
                    continue
                }
                if (name in entries) return invalid("It contains the same file twice.")
                val bytes = readCapped(zip, MAX_BYTES - total) ?: return invalid("It is larger than ${MAX_BYTES / (1024 * 1024)} MB once unpacked.")
                total += bytes.size
                entries[name] = bytes
            }
        } catch (e: TooLarge) {
            return invalid("It is larger than ${MAX_BYTES / (1024 * 1024)} MB.")
        } catch (e: ZipException) {
            return invalid("It is not a valid .uiscope file (the archive is damaged: ${e.javaClass.simpleName}).")
        } catch (e: IllegalArgumentException) {
            // The JDK reports undecodable file names this way.
            return invalid("It is not a valid .uiscope file (the archive is damaged: ${e.javaClass.simpleName}).")
        } catch (e: IOException) {
            return invalid("It could not be read (${e.javaClass.simpleName}).")
        }
        val captureBytes = entries[CAPTURE] ?: return invalid("It has no capture.json.")
        val capture = when (val c = CaptureJson.decode(String(captureBytes, Charsets.UTF_8), fallbackId = "imported")) {
            is Outcome.Failure -> return invalid(
                if (c.error.code == ErrorCodes.CAPTURE_SCHEMA_UNSUPPORTED) c.error.detail ?: "It was made by a newer UIScope." else "Its capture.json is not valid.",
            )
            is Outcome.Success -> c.value
        }
        val manifest = entries[MANIFEST]?.let { manifest(it) } ?: BundleManifest(BUNDLE_VERSION, "unknown", Instant.EPOCH, null, null)
        var shot: ByteArray? = null
        var warnings = capture.warnings
        var screenshot: Screenshot? = null
        entries[SCREENSHOT]?.let { png ->
            val size = pngSize(png)
            if (size == null) {
                warnings = warnings + CaptureWarning(ErrorCodes.SCREENSHOT_FAILED, "The bundle's screenshot is not a valid PNG and was left out")
            } else {
                shot = png
                screenshot = Screenshot(SCREENSHOT, size.first, size.second)
            }
        }
        return Outcome.Success(Bundle(capture.copy(screenshot = screenshot, warnings = warnings), shot, manifest))
    }

    /** Width and height from a PNG's IHDR chunk, or null when the bytes are not a PNG with a sane header. */
    fun pngSize(png: ByteArray): Pair<Int, Int>? {
        if (png.size < PNG_HEADER_BYTES) return null
        for (i in PNG_MAGIC.indices) if (png[i] != PNG_MAGIC[i]) return null
        fun int(at: Int) = ((png[at].toInt() and 0xFF) shl 24) or ((png[at + 1].toInt() and 0xFF) shl 16) or ((png[at + 2].toInt() and 0xFF) shl 8) or (png[at + 3].toInt() and 0xFF)
        if (String(png, 12, 4, Charsets.ISO_8859_1) != "IHDR") return null
        val w = int(16)
        val h = int(20)
        return if (w in 1..MAX_DIMENSION && h in 1..MAX_DIMENSION) w to h else null
    }

    private const val PNG_HEADER_BYTES = 24
    private const val MAX_DIMENSION = 32_768
    private val KNOWN = setOf(MANIFEST, CAPTURE, SCREENSHOT)

    private fun safeName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\") || name.contains('\\') || name.contains(':') || name.contains('\u0000')) return false
        return name.split('/').none { it == ".." }
    }

    private fun manifest(bytes: ByteArray): BundleManifest? {
        val root = (JsonReader.parse(String(bytes, Charsets.UTF_8)) as? Outcome.Success)?.value as? Map<*, *> ?: return null
        val app = root["app"] as? Map<*, *>
        return BundleManifest(
            bundleVersion = (root["bundleVersion"] as? Long)?.toInt() ?: BUNDLE_VERSION,
            appVersion = app?.get("version") as? String ?: "unknown",
            createdAt = (root["createdAt"] as? String)?.let { parseInstant(it) } ?: Instant.EPOCH,
            title = (root["title"] as? String)?.take(MAX_TEXT),
            note = (root["note"] as? String)?.take(MAX_TEXT),
        )
    }

    private const val MAX_TEXT = 4_000

    /** The manifest is informational, so an unreadable time is "unknown" (null), not an error. */
    private fun parseInstant(text: String): Instant? =
        try {
            Instant.parse(text)
        } catch (e: java.time.format.DateTimeParseException) {
            null.also { e.hashCode() }
        }

    /** Reads the current entry fully, or returns null when it exceeds [limit] bytes. */
    private fun readCapped(zip: ZipInputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun drain(zip: ZipInputStream, limit: Long): Long? {
        val buffer = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) return total
            total += n
            if (total > limit) return null
        }
    }

    private fun invalid(reason: String): Outcome.Failure =
        Outcome.Failure(CodedError(ErrorCodes.SNAPSHOT_IMPORT_INVALID, "That file can't be imported. $reason"))

    private class TooLarge : IOException("too large")

    /** Stops reading after [limit] bytes so a huge file is refused without being unpacked. */
    private class BoundedInput(private val inner: InputStream, private val limit: Long) : InputStream() {
        private var read = 0L

        override fun read(): Int {
            val b = inner.read()
            if (b >= 0 && ++read > limit) throw TooLarge()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) {
                read += n
                if (read > limit) throw TooLarge()
            }
            return n
        }
    }
}
