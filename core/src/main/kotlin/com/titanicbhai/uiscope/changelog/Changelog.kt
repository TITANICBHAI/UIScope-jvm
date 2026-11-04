package com.titanicbhai.uiscope.changelog

/** One bullet list of a release: "New", "Fixed", "Improved", ... */
data class ChangelogSection(val title: String, val items: List<String>)

/**
 * One version of the app as written in CHANGELOG.md. [intro] is the plain text before the first section; [sections] are in file order and
 * include the engineering history, which the screens leave out (see [shownSections]).
 */
data class ChangelogEntry(val version: String, val date: String?, val intro: String, val sections: List<ChangelogSection>) {
    /** The sections meant for people using the app: the ones whose title does not start with "Development history". */
    val shownSections: List<ChangelogSection> get() = sections.filterNot { it.title.startsWith(HISTORY_PREFIX, ignoreCase = true) }

    companion object {
        const val HISTORY_PREFIX = "Development history"
    }
}

/**
 * Reads the repository's CHANGELOG.md (the Keep a Changelog layout: `## [1.0.2] - 2026-11-04`, `### New`, `- item`) for the in-app
 * "What's new". Pure text in, values out: the file is bundled into the app at build time, so the changelog on the website, in the repository
 * and in the app is one document.
 */
object ChangelogParser {
    private val VERSION_HEADING = Regex("""^##\s+\[?(\d+\.\d+\.\d+)]?\s*(.*)$""")
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val SECTION_HEADING = Regex("""^###\s+(.*\S)\s*$""")
    private val BULLET = Regex("""^[-*]\s+(.*)$""")
    private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val BOLD = Regex("""\*\*([^*]+)\*\*""")

    /** Every version in the file, newest first as written. Text outside version blocks (the title, the format note) is ignored. */
    fun parse(markdown: String): List<ChangelogEntry> {
        val entries = ArrayList<ChangelogEntry>()
        var version: String? = null
        var date: String? = null
        val intro = StringBuilder()
        var sections = ArrayList<ChangelogSection>()
        var sectionTitle: String? = null
        var items = ArrayList<StringBuilder>()

        fun closeSection() {
            val title = sectionTitle
            if (title != null) sections += ChangelogSection(plain(title), items.map { plain(it.toString().trim()) }.filter { it.isNotEmpty() })
            sectionTitle = null
            items = ArrayList()
        }

        fun closeVersion() {
            closeSection()
            val v = version
            if (v != null) entries += ChangelogEntry(v, date, plain(intro.toString().trim()), sections)
            version = null
            date = null
            intro.setLength(0)
            sections = ArrayList()
        }

        for (raw in markdown.lineSequence()) {
            val line = raw.trimEnd()
            val heading = VERSION_HEADING.matchEntire(line)
            if (heading != null) {
                closeVersion()
                version = heading.groupValues[1]
                date = DATE.find(heading.groupValues[2])?.value
                continue
            }
            if (version == null) continue
            val section = SECTION_HEADING.matchEntire(line)
            if (section != null) {
                closeSection()
                sectionTitle = section.groupValues[1]
                continue
            }
            if (sectionTitle == null) {
                if (line.isNotBlank()) intro.append(if (intro.isEmpty()) "" else " ").append(line.trim())
                continue
            }
            // Bullets start in the first column; anything indented under one continues it.
            val bullet = if (line.startsWith("-") || line.startsWith("*")) BULLET.matchEntire(line) else null
            when {
                bullet != null -> items += StringBuilder(bullet.groupValues[1])
                line.isBlank() -> Unit
                items.isNotEmpty() -> items.last().append(' ').append(line.trim())
            }
        }
        closeVersion()
        return entries
    }

    /** Markdown for people: `**bold**`, `` `code` `` and `[text](url)` lose their markup. */
    fun plain(text: String): String = text.replace(LINK, "$1").replace(BOLD, "$1").replace("`", "")
}
