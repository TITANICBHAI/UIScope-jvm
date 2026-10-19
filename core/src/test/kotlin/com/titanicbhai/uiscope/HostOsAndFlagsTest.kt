package com.titanicbhai.uiscope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostOsAndFlagsTest {
    @Test
    fun `os names map to the right HostOs`() {
        assertEquals(HostOs.WINDOWS, HostOs.fromOsName("Windows 11"))
        assertEquals(HostOs.WINDOWS, HostOs.fromOsName("Windows 10"))
        assertEquals(HostOs.MACOS, HostOs.fromOsName("Mac OS X"))
        assertEquals(HostOs.MACOS, HostOs.fromOsName("Darwin"))
        assertEquals(HostOs.LINUX, HostOs.fromOsName("Linux"))
        assertEquals(HostOs.OTHER, HostOs.fromOsName("FreeBSD"))
        assertEquals(HostOs.OTHER, HostOs.fromOsName(null))
    }

    @Test
    fun `windows pc inspection is only on for Windows and diff is always off`() {
        assertTrue(FeatureFlags.forHost(HostOs.WINDOWS).windowsPc)
        assertFalse(FeatureFlags.forHost(HostOs.MACOS).windowsPc)
        assertFalse(FeatureFlags.forHost(HostOs.LINUX).windowsPc)
        HostOs.entries.forEach { assertFalse(FeatureFlags.forHost(it).diff, "diff must stay off for $it") }
    }
}
