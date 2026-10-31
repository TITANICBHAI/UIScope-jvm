package com.titanicbhai.uiscope

/**
 * Compile-time-simple switches for capabilities that are not available everywhere or not shipped yet.
 *
 * - [windowsPc]: inspecting Windows application windows. Only meaningful on Windows (plan §1.4 #1, #12).
 * - [diff]: the Compare screen (plan Phase 6). The gate existed only until the matching engine (P6-1) and the
 *   Compare UI (P6-2) were rebuilt and verified — both are, so the flag is on everywhere (plan §1.4 #9).
 */
data class FeatureFlags(
    val windowsPc: Boolean,
    val diff: Boolean = true,
) {
    companion object {
        fun forHost(os: HostOs = HostOs.current()): FeatureFlags =
            FeatureFlags(windowsPc = os == HostOs.WINDOWS, diff = true)
    }
}
