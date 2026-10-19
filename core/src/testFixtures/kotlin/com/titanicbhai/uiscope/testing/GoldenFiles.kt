package com.titanicbhai.uiscope.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.fail

/**
 * Golden-file assertions for generated text (codegen, XML, markdown exports).
 *
 * Compares [assertMatches]' `actual` with `src/test/resources/golden/<name>` (line endings normalised).
 * To (re)create or update goldens after an intended change, run the tests with `UPDATE_GOLDEN=1` in the environment
 * (or `-Dgolden.update=true`), then review the diff in version control.
 */
object GoldenFiles {
    private val updateRequested: Boolean
        get() = System.getenv("UPDATE_GOLDEN") == "1" || System.getProperty("golden.update") == "true"

    fun assertMatches(name: String, actual: String, dir: Path = Path.of("src", "test", "resources", "golden")) {
        val file = dir.resolve(name)
        val normalizedActual = normalize(actual)
        if (updateRequested) {
            val current = if (Files.exists(file)) normalize(Files.readString(file)) else null
            if (current != normalizedActual) {
                Files.createDirectories(file.parent)
                Files.writeString(file, normalizedActual)
            }
            return
        }
        if (!Files.exists(file)) {
            fail("Golden file '$name' does not exist at $file. Run the tests with UPDATE_GOLDEN=1 to create it, then review it.")
        }
        val expected = normalize(Files.readString(file))
        if (expected != normalizedActual) {
            fail("Output differs from golden file '$name'.\n${firstDifference(expected, normalizedActual)}\nRun with UPDATE_GOLDEN=1 if the change is intended.")
        }
    }

    private fun normalize(text: String): String = text.replace("\r\n", "\n")

    private fun firstDifference(expected: String, actual: String): String {
        val e = expected.split('\n')
        val a = actual.split('\n')
        val line = (0 until maxOf(e.size, a.size)).firstOrNull { e.getOrNull(it) != a.getOrNull(it) } ?: return "(no line difference)"
        return "First difference at line ${line + 1}:\n  expected: ${e.getOrNull(line) ?: "<end of file>"}\n  actual:   ${a.getOrNull(line) ?: "<end of file>"}"
    }
}
