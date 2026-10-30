package com.titanicbhai.uiscope.json

import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.testing.HostileStrings
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonTest {
    private fun parse(text: String) = JsonReader.parse(text).assertSuccess()

    @Test
    fun `values of every kind`() {
        @Suppress("UNCHECKED_CAST")
        val map = parse("""{"s":"a\"b\\c\/d\n\t\u00e9\ud83d\ude00","i":-12,"big":9223372036854775807,"d":1.5e2,"t":true,"f":false,"n":null,"a":[1,[2,{"x":3}]],"o":{}}""") as Map<String, Any?>
        assertEquals("a\"b\\c/d\n\t\u00e9\uD83D\uDE00", map["s"])
        assertEquals(-12L, map["i"])
        assertEquals(Long.MAX_VALUE, map["big"])
        assertEquals(150.0, map["d"])
        assertEquals(true, map["t"])
        assertEquals(false, map["f"])
        assertEquals(null, map["n"])
        assertEquals(listOf(1L, listOf(2L, mapOf("x" to 3L))), map["a"])
        assertEquals(emptyMap<String, Any?>(), map["o"])
    }

    @Test
    fun `a byte order mark and surrounding whitespace are fine`() {
        assertEquals(mapOf("a" to 1L), parse("\uFEFF \n\t{ \"a\" : 1 }\r\n"))
    }

    @Test
    fun `malformed documents are errors with an offset and no echo of the content`() {
        val bad = listOf("", "{", "{\"a\":}", "{\"a\" 1}", "{a:1}", "[1,]", "[1 2]", "{\"a\":1,}", "\"abc", "tru", "01", "1.", "-", "{\"a\":1} trailing", "'single'", "{\"a\":\"x\ny\"}", "\"\\x\"", "\"\\u12\"", "nul")
        for (text in bad) {
            val error = JsonReader.parse(text).assertFailure(ErrorCodes.PARSE_FAILED)
            assertTrue(error.detail!!.contains("offset"), "$text: ${error.detail}")
        }
        val secret = JsonReader.parse("{\"password\":\"SECRET-VALUE\" oops}").assertFailure()
        assertTrue(!secret.detail!!.contains("SECRET"), "the error must not repeat file content")
    }

    @Test
    fun `nesting is limited`() {
        val ok = "[".repeat(JsonReader.MAX_DEPTH) + "]".repeat(JsonReader.MAX_DEPTH)
        parse(ok)
        val tooDeep = "[".repeat(JsonReader.MAX_DEPTH + 5) + "]".repeat(JsonReader.MAX_DEPTH + 5)
        JsonReader.parse(tooDeep).assertFailure(ErrorCodes.PARSE_FAILED)
        JsonReader.parse("[".repeat(100_000)).assertFailure(ErrorCodes.PARSE_FAILED)
    }

    @Test
    fun `every hostile string survives write then read, and the output is plain ASCII`() {
        for ((name, value) in HostileStrings.all) {
            val literal = JsonWriter.string(value)
            assertTrue(literal.all { it in ' '..'~' }, "$name: the literal must be plain ASCII")
            assertEquals(value, parse(literal), name)
        }
        assertEquals("\"\\u0000\\u001f\\u007f\"", JsonWriter.string("\u0000\u001f\u007f"))
    }
}
