package com.titanicbhai.uiscope.adb

import java.io.ByteArrayOutputStream

/**
 * Finds the PNG inside whatever `adb ... screencap -p` printed.
 *
 * `adb exec-out` has no separate error stream, so a warning from the device ("[Warning: multiple displays found ...]") lands in front of
 * the picture, and the older `shell` service on Windows turned every LF into CR LF. Both used to make a perfectly good screenshot look
 * like "adb did not return a PNG image", which is how the phone's screen never appeared.
 */
internal object PngBytes {
    class Found(val bytes: ByteArray, val width: Int, val height: Int)

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** The signature after a CR LF translation: the `0D 0A` pairs have become `0D 0D 0A`. */
    private val SIGNATURE_CRLF = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0D, 0x0A, 0x1A, 0x0D, 0x0A)
    private val IEND = byteArrayOf(0x49, 0x45, 0x4E, 0x44)

    private const val MAX_LEADING = 64 * 1024
    private const val MAX_TRAILING = 4 * 1024
    private const val CRC_SIZE = 4
    private const val MIN_SIZE = 33 // signature + IHDR chunk
    private const val WIDTH_AT = 16
    private const val HEIGHT_AT = 20
    private const val MAX_SIDE = 32_768

    /** The PNG found in [raw] (leading noise and trailing noise cut away, CR LF damage repaired), or null when there is none. */
    fun find(raw: ByteArray): Found? {
        val plain = indexOf(raw, SIGNATURE, MAX_LEADING)
        val bytes = if (plain >= 0) {
            trim(raw, plain)
        } else {
            val mangled = indexOf(raw, SIGNATURE_CRLF, MAX_LEADING)
            if (mangled < 0) return null
            trim(repairLineEndings(raw.copyOfRange(mangled, raw.size)), 0)
        }
        if (bytes.size < MIN_SIZE) return null
        val width = readInt(bytes, WIDTH_AT)
        val height = readInt(bytes, HEIGHT_AT)
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return null
        return Found(bytes, width, height)
    }

    /** Cuts everything after the IEND chunk (and its CRC); when no IEND can be found the rest is kept as it is. */
    private fun trim(raw: ByteArray, start: Int): ByteArray {
        val lowest = maxOf(start, raw.size - MAX_TRAILING)
        var at = raw.size - IEND.size
        while (at >= lowest) {
            if (matches(raw, at, IEND)) return raw.copyOfRange(start, minOf(at + IEND.size + CRC_SIZE, raw.size))
            at--
        }
        return raw.copyOfRange(start, raw.size)
    }

    private fun repairLineEndings(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size)
        var i = 0
        while (i < raw.size) {
            if (raw[i] == CR && i + 1 < raw.size && raw[i + 1] == LF) {
                out.write(LF.toInt())
                i += 2
            } else {
                out.write(raw[i].toInt())
                i++
            }
        }
        return out.toByteArray()
    }

    private fun indexOf(raw: ByteArray, needle: ByteArray, maxStart: Int): Int {
        val last = minOf(raw.size - needle.size, maxStart)
        var at = 0
        while (at <= last) {
            if (matches(raw, at, needle)) return at
            at++
        }
        return -1
    }

    private fun matches(raw: ByteArray, at: Int, needle: ByteArray): Boolean {
        for (k in needle.indices) if (raw[at + k] != needle[k]) return false
        return true
    }

    private fun readInt(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

    private const val CR: Byte = 0x0D
    private const val LF: Byte = 0x0A
}
