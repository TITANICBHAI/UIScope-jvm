package com.titanicbhai.uiscope

/**
 * A semantic version (semver.org 2.0): `MAJOR.MINOR.PATCH[-prerelease][+build]`, optionally with a leading `v`
 * (GitHub tags). Build metadata is ignored when comparing. A pre-release sorts before its release.
 */
class SemVer private constructor(
    val major: Long,
    val minor: Long,
    val patch: Long,
    private val preRelease: List<String>,
) : Comparable<SemVer> {

    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1 // a release is newer than its own pre-releases
        if (other.preRelease.isEmpty()) return -1
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val a = preRelease[i]
            val b = other.preRelease[i]
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers sort before alphanumeric ones
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun equals(other: Any?) = other is SemVer && compareTo(other) == 0

    override fun hashCode() = listOf(major, minor, patch, preRelease).hashCode()

    override fun toString() = "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        private val PATTERN = Regex("""v?(\d{1,9})\.(\d{1,9})\.(\d{1,9})(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?""")

        /** The parsed version, or null when [text] is not a semantic version. Never throws. */
        fun parse(text: String): SemVer? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            val pre = m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            if (pre.any { it.length > 1 && it.all(Char::isDigit) && it.startsWith("0") }) return null // numeric identifiers have no leading zeros
            return SemVer(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong(), pre)
        }
    }
}
