package com.titanicbhai.uiscope.ui.theme

import androidx.compose.material3.ColorScheme
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/** G-THEME (plan P4-1, §4.12): every text/background pair of the theme tokens holds at least 4.5:1 contrast in BOTH themes. */
class ThemeContrastTest {

    @Test
    fun `light theme text pairs hold at least 4_5 to 1 contrast`() = assertContrast(UiScopeLightColors)

    @Test
    fun `dark theme text pairs hold at least 4_5 to 1 contrast`() = assertContrast(UiScopeDarkColors)

    private fun assertContrast(scheme: ColorScheme) {
        val pairs = listOf(
            "onBackground on background" to (scheme.onBackground to scheme.background),
            "onSurface on surface" to (scheme.onSurface to scheme.surface),
            "onSurfaceVariant on surfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
            "onPrimary on primary" to (scheme.onPrimary to scheme.primary),
            "onPrimaryContainer on primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
            "onSecondary on secondary" to (scheme.onSecondary to scheme.secondary),
            "onSecondaryContainer on secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
            "onTertiary on tertiary" to (scheme.onTertiary to scheme.tertiary),
            "onError on error" to (scheme.onError to scheme.error),
        )
        for ((name, pair) in pairs) {
            val ratio = contrastRatio(pair.first, pair.second)
            assertTrue(ratio >= 4.5, "$name has contrast $ratio:1, the plan requires at least 4.5:1")
        }
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    /** WCAG 2.x relative luminance of an sRGB colour. */
    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
