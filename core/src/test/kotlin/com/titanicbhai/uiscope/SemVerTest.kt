package com.titanicbhai.uiscope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M17: the old check offered "downgrades" because it compared with `!=`. */
class SemVerTest {
    private fun v(s: String) = SemVer.parse(s) ?: error("not a version: $s")

    @Test
    fun `numbers compare as numbers not as text`() {
        assertTrue(v("1.10.0") > v("1.9.0"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("1.0.10") > v("1.0.9"))
        assertTrue(v("1.0.1") > v("1.0.0"))
        assertEquals(v("1.2.3"), v("v1.2.3"))
        assertEquals(v("1.2.3"), v("1.2.3+build.7"))
    }

    @Test
    fun `a downgrade is never greater`() {
        assertTrue(v("0.9.0") < v("1.0.0"))
        assertTrue(v("1.0.0") <= v("1.0.0"))
    }

    @Test
    fun `semver precedence for pre-releases`() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        for (i in 0 until ordered.size - 1) assertTrue(v(ordered[i]) < v(ordered[i + 1]), "${ordered[i]} should be below ${ordered[i + 1]}")
        assertTrue(v("1.0.0-rc.1").isPreRelease)
        assertTrue(!v("1.0.0").isPreRelease)
    }

    @Test
    fun `things that are not versions are null and never throw`() {
        for (bad in listOf("", "1", "1.0", "1.0.0.0", "a.b.c", "1.0.x", "latest", "1.0.0-", "1.0.0-01", "1.-1.0", "9999999999.0.0", " ", "1.0.0-a..b", "V1.0.0\u0000")) {
            assertNull(SemVer.parse(bad), "'$bad' should not parse")
        }
        assertNotNull(SemVer.parse("  v1.0.0 "))
    }

    @Test
    fun `toString drops the v and the build part`() {
        assertEquals("1.2.3-rc.1", v("v1.2.3-rc.1+abc").toString())
    }
}
