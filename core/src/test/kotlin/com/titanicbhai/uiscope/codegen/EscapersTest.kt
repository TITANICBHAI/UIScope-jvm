package com.titanicbhai.uiscope.codegen

import com.titanicbhai.uiscope.testing.HostileStrings
import com.titanicbhai.uiscope.testing.withTempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Appendix G4 (fixes B6): every escaper round-trips every hostile string, checked by an independent reader of that language. */
class EscapersTest {
    companion object {
        /** `python3` or `python`, whichever runs; null when neither does (a developer machine without Python). */
        val python: String? by lazy {
            listOf("python3", "python").firstOrNull { cmd ->
                try {
                    ProcessBuilder(cmd, "--version").redirectErrorStream(true).start().let { it.waitFor(20, TimeUnit.SECONDS) && it.exitValue() == 0 }
                } catch (e: java.io.IOException) {
                    println("no $cmd here: ${e.message}")
                    false
                }
            }
        }

        fun runPython(vararg args: String, script: String? = null): Pair<Int, ByteArray> {
            val cmd = listOf(python!!) + args
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            if (script != null) process.outputStream.use { it.write(script.toByteArray(Charsets.UTF_8)) } else process.outputStream.close()
            val out = process.inputStream.readAllBytes()
            check(process.waitFor(60, TimeUnit.SECONDS)) { "python timed out" }
            return process.exitValue() to out
        }
    }

    @Test
    fun `python literals evaluate to the original text`() {
        python ?: return println("SKIPPED: no python on this machine (CI has it)")
        withTempDir { dir ->
            HostileStrings.all.forEach { (name, text) ->
                val file = dir.resolve("$name.py")
                // print the code points of the evaluated literal; comparing code points also covers lone surrogates
                Files.writeString(file, "x = ${Escapers.python(text)}\nprint(' '.join('%x' % ord(ch) for ch in x))\n")
                val (code, out) = runPython(file.toString())
                assertEquals(0, code, "$name: ${out.toString(Charsets.UTF_8)}")
                val expected = text.codePoints().toArray().joinToString(" ") { it.toString(16) }
                assertEquals(expected, out.toString(Charsets.UTF_8).trim(), name)
            }
        }
    }

    @Test
    fun `yaml literals load back to the original text`() {
        (HostileStrings.common + HostileStrings.controls).forEach { (name, text) ->
            assertEquals(text, Yaml().load<String>(Escapers.yaml(text)), name)
        }
    }

    @Test
    fun `xpath literals evaluate to the original text with XML-illegal characters removed`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream("<a/>".toByteArray()))
        (HostileStrings.common + HostileStrings.controls + HostileStrings.loneSurrogate).forEach { (name, text) ->
            val expected = com.titanicbhai.uiscope.export.XmlExporter.stripInvalidXmlChars(text)
            val result = XPathFactory.newInstance().newXPath().evaluate("string(${Escapers.xpath(text)})", doc, XPathConstants.STRING) as String
            assertEquals(expected, result, name)
        }
        assertEquals("'plain'", Escapers.xpath("plain"))
        assertEquals("\"it's\"", Escapers.xpath("it's"))
        assertEquals("concat('say \"hi\" it', \"'\", 's')", Escapers.xpath("say \"hi\" it's"))
    }

    @Test
    fun `regex literals match only the original`() {
        (HostileStrings.common + HostileStrings.controls).forEach { (name, text) ->
            val regex = Escapers.regexLiteral(text)
            assertTrue(Pattern.matches(regex, text), "$name must match itself")
            assertFalse(Pattern.matches(regex, text + "x"), "$name must not match a longer text")
        }
        assertEquals("a\\.b\\*", Escapers.regexLiteral("a.b*"))
    }

    // ── languages without an interpreter here: explicit expectations plus a reader that follows the language specification ──

    @Test
    fun `java literals use octal for controls and round trip`() {
        assertEquals("\"Don't allow\"", Escapers.java("Don't allow"))
        assertEquals("\"Say \\\"hi\\\"\"", Escapers.java("Say \"hi\""))
        assertEquals("\"a\\001b\\177c\"", Escapers.java("a\u0001b\u007fc"), "never \\u000a-style escapes: Java translates them before lexing")
        assertEquals("\"\\\\u0041\"", Escapers.java("\\u0041"))
        (HostileStrings.common + HostileStrings.controls).forEach { (name, text) -> assertEquals(text, readJava(Escapers.java(text)), name) }
        assertFalse(Escapers.java(HostileStrings.all.values.joinToString("")).contains("\\u000"), "no unicode escape for a line terminator or quote")
    }

    @Test
    fun `kotlin literals escape dollar and round trip`() {
        assertEquals("\"\\\$dollar and \\\${braces}\"", Escapers.kotlin("\$dollar and \${braces}"))
        assertEquals("\"a\\u0001b\"", Escapers.kotlin("a\u0001b"))
        (HostileStrings.common + HostileStrings.controls).forEach { (name, text) -> assertEquals(text, readJava(Escapers.kotlin(text).replace("\\$", "$")), name) }
    }

    @Test
    fun `csharp literals round trip`() {
        assertEquals("\"a\\0b\\u0001\"", Escapers.csharp("a\u0000b\u0001"))
        (HostileStrings.common + HostileStrings.controls).forEach { (name, text) -> assertEquals(text, readJava(Escapers.csharp(text).replace("\\0", "\\000")), name) }
    }

    @Test
    fun `powershell doubles every quote look-alike and builds controls from char codes`() {
        assertEquals("'Don''t allow'", Escapers.powershell("Don't allow"))
        assertEquals("'it’’s ‘‘x’’'", Escapers.powershell("it\u2019s \u2018x\u2019"))
        assertEquals("('a' + [char]10 + 'b')", Escapers.powershell("a\nb"))
        assertEquals("('' + [char]1 + 'x')", Escapers.powershell("\u0001x"))
        assertEquals("''", Escapers.powershell(""))
        assertEquals("('x' + [char]55296 + 'y')", Escapers.powershell("x\uD800y"))
        (HostileStrings.all).forEach { (name, text) -> assertEquals(text, readPowerShell(Escapers.powershell(text)), name) }
    }

    @Test
    fun `autohotkey uses backtick escapes and Chr for other controls`() {
        assertEquals("\"say `\"hi`\"\"", Escapers.autohotkey("say \"hi\""))
        assertEquals("\"``tick``\"", Escapers.autohotkey("`tick`"))
        assertEquals("\"a`nb`tc\"", Escapers.autohotkey("a\nb\tc"))
        assertEquals("(\"a\" . Chr(1) . \"b\")", Escapers.autohotkey("a\u0001b"))
        assertEquals("(\"x\" . Chr(55296) . \"y\")", Escapers.autohotkey("x\uD800y"))
        (HostileStrings.all).forEach { (name, text) -> assertEquals(text, readAutoHotkey(Escapers.autohotkey(text)), name) }
    }

    // ── tiny readers (self-consistency against the language rules, not against our own escaper's code) ──

    private fun readJava(literal: String): String {
        val s = literal.substring(1, literal.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\') { out.append(c); continue }
            when (val e = s[i++]) {
                'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t'); 'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                '\\' -> out.append('\\'); '"' -> out.append('"'); '\'' -> out.append('\'')
                'u' -> { out.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                in '0'..'7' -> { var n = e - '0'; var digits = 1; while (digits < 3 && i < s.length && s[i] in '0'..'7') { n = n * 8 + (s[i++] - '0'); digits++ }; out.append(n.toChar()) }
                '$' -> out.append('$')
                else -> error("unknown escape \\$e")
            }
        }
        return out.toString()
    }

    private val quotes = "'\u2018\u2019\u201a\u201b"

    private fun readPowerShell(expr: String): String {
        val s = expr.trim().removePrefix("(").removeSuffix(")")
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            when {
                s[i] == ' ' || s[i] == '+' -> i++
                s[i] in quotes -> {
                    i++
                    while (true) {
                        val c = s[i]
                        if (c in quotes) {
                            if (i + 1 < s.length && s[i + 1] in quotes) { out.append(c); i += 2 } else { i++; break }
                        } else { out.append(c); i++ }
                    }
                }
                s.startsWith("[char]", i) -> { val end = s.indexOfAny(charArrayOf(' ', ')'), i).let { if (it < 0) s.length else it }; out.append(s.substring(i + 6, end).toInt().toChar()); i = end }
                else -> error("cannot read PowerShell at $i: $s")
            }
        }
        return out.toString()
    }

    private fun readAutoHotkey(expr: String): String {
        val s = expr.trim().removePrefix("(").removeSuffix(")")
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            when {
                s[i] == ' ' || s[i] == '.' -> i++
                s[i] == '"' -> {
                    i++
                    while (s[i] != '"') {
                        if (s[i] == '`') {
                            i++
                            when (s[i]) { 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t'); else -> out.append(s[i]) }
                        } else out.append(s[i])
                        i++
                    }
                    i++
                }
                s.startsWith("Chr(", i) -> { val end = s.indexOf(')', i); out.append(s.substring(i + 4, end).toInt().toChar()); i = end + 1 }
                else -> error("cannot read AutoHotkey at $i: $s")
            }
        }
        return out.toString()
    }
}
