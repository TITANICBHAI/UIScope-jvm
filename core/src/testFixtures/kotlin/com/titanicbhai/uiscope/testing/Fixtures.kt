package com.titanicbhai.uiscope.testing

import kotlin.test.fail

/**
 * Loads files from `core/src/test/resources/fixtures/` (shared with engine tests through its test resources).
 * Bytes are returned exactly as stored: CRLF, byte-order marks and trailing text are part of the fixtures.
 */
object Fixtures {
    fun bytes(path: String): ByteArray {
        val stream = Fixtures::class.java.classLoader.getResourceAsStream("fixtures/$path")
            ?: fail("Fixture 'fixtures/$path' was not found on the test classpath")
        return stream.use { it.readAllBytes() }
    }

    /** UTF-8 text with line endings untouched. */
    fun text(path: String): String = bytes(path).toString(Charsets.UTF_8)

    /** A raw `adb exec-out uiautomator dump` sample from `fixtures/android/`. */
    fun androidDump(name: String): String = text("android/$name")
}
