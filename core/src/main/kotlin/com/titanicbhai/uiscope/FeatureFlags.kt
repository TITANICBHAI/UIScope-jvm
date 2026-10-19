package com.titanicbhai.uiscope

/**
 * Compile-time-simple switches for capabilities that are not available everywhere or not shipped yet.
 *
 * - [windowsPc]: inspecting Windows application windows. Only meaningful on Windows (plan §1.4 #1, #12).
 * - [diff]: the Compare screen. Hidden until it is rebuilt and verified (Phase 6, plan §1.4 #9).
 */
data class FeatureFlags(
    val windowsPc: Boolean,
    val diff: Boolean = false,
) {
    companion object {
        fun forHost(os: HostOs = HostOs.current()): FeatureFlags =
            FeatureFlags(windowsPc = os == HostOs.WINDOWS, diff = false)
    }
}
