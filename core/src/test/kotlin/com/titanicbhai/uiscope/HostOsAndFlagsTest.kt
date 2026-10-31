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
    fun `windows pc inspection is only on for Windows and the diff gate has been removed`() {
        assertTrue(FeatureFlags.forHost(HostOs.WINDOWS).windowsPc)
        assertFalse(FeatureFlags.forHost(HostOs.MACOS).windowsPc)
        assertFalse(FeatureFlags.forHost(HostOs.LINUX).windowsPc)
        // P6-1 (the matching engine) and P6-2 (the Compare UI) are rebuilt and verified, so the diff
        // flag is on for every host (plan §1.4 #9: the gate exists only until both pass).
        HostOs.entries.forEach { assertTrue(FeatureFlags.forHost(it).diff, "Compare must be on for $it") }
    }
}
