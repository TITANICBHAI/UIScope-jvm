package com.titanicbhai.uiscope.adb

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The picture inside whatever adb printed: noise in front, noise behind, CR LF damage, and things that are not pictures at all. */
class PngBytesTest {
    private fun png(width: Int = 120, height: Int = 240): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, (x * 2 shl 16) or (y shl 8) or (x xor y))
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun `a clean PNG is found as it is, with its size`() {
        val bytes = png()
        val found = assertNotNull(PngBytes.find(bytes))
        assertContentEquals(bytes, found.bytes)
        assertEquals(120, found.width)
        assertEquals(240, found.height)
    }

    @Test
    fun `a warning in front of the picture is cut away`() {
        val bytes = png()
        val found = assertNotNull(PngBytes.find("[Warning: multiple displays found]\n".toByteArray() + bytes))
        assertContentEquals(bytes, found.bytes)
    }

    @Test
    fun `noise behind the picture is cut away at the end of the PNG`() {
        val bytes = png()
        val found = assertNotNull(PngBytes.find(bytes + "\nshell exited with 0\n".toByteArray()))
        assertContentEquals(bytes, found.bytes)
    }

    @Test
    fun `a picture whose line endings were turned into CR LF is repaired`() {
        val bytes = png()
        val damaged = ByteArrayOutputStream().also { out ->
            for (b in bytes) {
                if (b == 0x0A.toByte()) out.write(0x0D)
                out.write(b.toInt())
            }
        }.toByteArray()
        val found = assertNotNull(PngBytes.find(damaged))
        assertContentEquals(bytes, found.bytes)
        assertNotNull(ImageIO.read(found.bytes.inputStream()), "and it decodes")
    }

    @Test
    fun `text, nothing, a truncated header and absurd sizes are not pictures`() {
        assertNull(PngBytes.find(ByteArray(0)))
        assertNull(PngBytes.find("adb: error: device offline\n".toByteArray()))
        assertNull(PngBytes.find(png().copyOf(20)))
        val absurd = png().also { b -> for (i in 16..19) b[i] = 0x7F }
        assertNull(PngBytes.find(absurd), "a width of two billion pixels is not a phone")
    }

    @Test
    fun `a signature far into the output is not mistaken for the picture`() {
        val farAway = ByteArray(200_000) { 'x'.code.toByte() } + png()
        assertNull(PngBytes.find(farAway), "only the start of the output is searched, so a stray byte run cannot be a screenshot")
    }
}
