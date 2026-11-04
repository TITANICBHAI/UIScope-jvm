package com.titanicbhai.uiscope.changelog

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The in-app "What's new" reads CHANGELOG.md; these tests pin both the reader and the file it reads. */
class ChangelogParserTest {
    private val sample = """
        # Changelog

        Format note that belongs to no version.

        ## [1.1.0] - 2026-11-03

        First public release. Short and friendly.

        ### New
        - **Bold** thing with `code` and a [link](https://example.org/x).
        - A second item that wraps
          onto a continuation line.

        ### Fixed
        - Something broke, now it does not.

        ### Development history (not shown in the app)
        - Engineering detail.

        ## [1.0.1] - unreleased (never published)

        ### Added
        * Star bullets work too.
    """.trimIndent()

    @Test
    fun `versions, dates, intro and sections are read in file order`() {
        val entries = ChangelogParser.parse(sample)
        assertEquals(listOf("1.1.0", "1.0.1"), entries.map { it.version })
        assertEquals("2026-11-03", entries[0].date)
        assertNull(entries[1].date, "a heading without a date has none")
        assertEquals("First public release. Short and friendly.", entries[0].intro)
        assertEquals(listOf("New", "Fixed", "Development history (not shown in the app)"), entries[0].sections.map { it.title })
    }

    @Test
    fun `markup is stripped and wrapped bullets are joined`() {
        val new = ChangelogParser.parse(sample)[0].sections[0]
        assertEquals("Bold thing with code and a link.", new.items[0])
        assertEquals("A second item that wraps onto a continuation line.", new.items[1])
        assertEquals(listOf("Star bullets work too."), ChangelogParser.parse(sample)[1].sections[0].items)
    }

    @Test
    fun `the engineering history is not part of what people are shown`() {
        val entry = ChangelogParser.parse(sample)[0]
        assertEquals(listOf("New", "Fixed"), entry.shownSections.map { it.title })
    }

    @Test
    fun `text without any version gives no entries`() {
        assertTrue(ChangelogParser.parse("").isEmpty())
        assertTrue(ChangelogParser.parse("# Changelog\n\nNothing here.\n").isEmpty())
    }

    @Test
    fun `windows line endings read the same`() {
        assertEquals(ChangelogParser.parse(sample), ChangelogParser.parse(sample.replace("\n", "\r\n")))
    }

    @Test
    fun `the real CHANGELOG md has the current version on top, with something to show`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "version.txt").isFile && File(it, "CHANGELOG.md").isFile }
        assertNotNull(root, "version.txt and CHANGELOG.md sit in the repository root")
        val version = File(root, "version.txt").readText().trim()
        val entries = ChangelogParser.parse(File(root, "CHANGELOG.md").readText())
        assertTrue(entries.isNotEmpty(), "CHANGELOG.md has at least one version")
        assertEquals(version, entries.first().version, "bumping version.txt means writing its CHANGELOG.md entry first")
        val shown = entries.first().shownSections
        assertTrue(shown.isNotEmpty() && shown.all { it.items.isNotEmpty() }, "the newest entry has user-facing items")
        assertFalse(entries.first().date.isNullOrBlank(), "the newest entry carries its release date")
    }
}
