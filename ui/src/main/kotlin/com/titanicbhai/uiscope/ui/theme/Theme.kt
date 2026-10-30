package com.titanicbhai.uiscope.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.settings.ThemeMode

/**
 * Design tokens (plan P4-1, §4.12): spacing, corners and the raw palette live here once. The two color schemes are built from
 * them; every text/background pair must hold a contrast ratio of at least 4.5:1 in both themes — `ThemeContrastTest` (G-THEME)
 * enforces that, so a colour change that breaks accessibility fails the build instead of the screen.
 */
object UiScopeTokens {
    // Spacing scale.
    val spaceXs: Dp = 4.dp
    val spaceS: Dp = 8.dp
    val spaceM: Dp = 12.dp
    val spaceL: Dp = 16.dp
    val spaceXl: Dp = 24.dp
    val spaceXxl: Dp = 32.dp

    // Shape scale.
    val cornerSmall = RoundedCornerShape(4.dp)
    val cornerMedium = RoundedCornerShape(8.dp)
    val cornerLarge = RoundedCornerShape(12.dp)

    // Raw palette — light.
    val LightBackground = Color(0xFFFFFFFF)
    val LightOnBackground = Color(0xFF1A1C1E)
    val LightSurface = Color(0xFFF8F9FC)
    val LightOnSurface = Color(0xFF1A1C1E)
    val LightSurfaceVariant = Color(0xFFECEDF1)
    val LightOnSurfaceVariant = Color(0xFF43474E)
    val LightPrimary = Color(0xFF1A6EC7)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightPrimaryContainer = Color(0xFFD6E4FF)
    val LightOnPrimaryContainer = Color(0xFF001D3D)
    val LightSecondary = Color(0xFF177232)
    val LightOnSecondary = Color(0xFFFFFFFF)
    val LightSecondaryContainer = Color(0xFFD3F5DC)
    val LightOnSecondaryContainer = Color(0xFF00210D)
    val LightTertiary = Color(0xFF6B4FA8)
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightError = Color(0xFFBA1A1A)
    val LightOnError = Color(0xFFFFFFFF)
    val LightOutline = Color(0xFFCDD0D5)

    // Raw palette — dark.
    val DarkBackground = Color(0xFF121316)
    val DarkOnBackground = Color(0xFFE3E2E6)
    val DarkSurface = Color(0xFF17181C)
    val DarkOnSurface = Color(0xFFE3E2E6)
    val DarkSurfaceVariant = Color(0xFF23252B)
    val DarkOnSurfaceVariant = Color(0xFFC0C4CC)
    val DarkPrimary = Color(0xFF7CB8F4)
    val DarkOnPrimary = Color(0xFF121316)
    val DarkPrimaryContainer = Color(0xFF1C3A5E)
    val DarkOnPrimaryContainer = Color(0xFFB9D7FF)
    val DarkSecondary = Color(0xFF86D7A0)
    val DarkOnSecondary = Color(0xFF121316)
    val DarkSecondaryContainer = Color(0xFF0F3320)
    val DarkOnSecondaryContainer = Color(0xFFA8E6BC)
    val DarkTertiary = Color(0xFFC9A8F4)
    val DarkOnTertiary = Color(0xFF121316)
    val DarkError = Color(0xFFFFB4AB)
    val DarkOnError = Color(0xFF690005)
    val DarkOutline = Color(0xFF8B909A)
}

val UiScopeLightColors = lightColorScheme(
    primary = UiScopeTokens.LightPrimary,
    onPrimary = UiScopeTokens.LightOnPrimary,
    primaryContainer = UiScopeTokens.LightPrimaryContainer,
    onPrimaryContainer = UiScopeTokens.LightOnPrimaryContainer,
    secondary = UiScopeTokens.LightSecondary,
    onSecondary = UiScopeTokens.LightOnSecondary,
    secondaryContainer = UiScopeTokens.LightSecondaryContainer,
    onSecondaryContainer = UiScopeTokens.LightOnSecondaryContainer,
    tertiary = UiScopeTokens.LightTertiary,
    onTertiary = UiScopeTokens.LightOnTertiary,
    error = UiScopeTokens.LightError,
    onError = UiScopeTokens.LightOnError,
    background = UiScopeTokens.LightBackground,
    onBackground = UiScopeTokens.LightOnBackground,
    surface = UiScopeTokens.LightSurface,
    onSurface = UiScopeTokens.LightOnSurface,
    surfaceVariant = UiScopeTokens.LightSurfaceVariant,
    onSurfaceVariant = UiScopeTokens.LightOnSurfaceVariant,
    outline = UiScopeTokens.LightOutline,
)

val UiScopeDarkColors = darkColorScheme(
    primary = UiScopeTokens.DarkPrimary,
    onPrimary = UiScopeTokens.DarkOnPrimary,
    primaryContainer = UiScopeTokens.DarkPrimaryContainer,
    onPrimaryContainer = UiScopeTokens.DarkOnPrimaryContainer,
    secondary = UiScopeTokens.DarkSecondary,
    onSecondary = UiScopeTokens.DarkOnSecondary,
    secondaryContainer = UiScopeTokens.DarkSecondaryContainer,
    onSecondaryContainer = UiScopeTokens.DarkOnSecondaryContainer,
    tertiary = UiScopeTokens.DarkTertiary,
    onTertiary = UiScopeTokens.DarkOnTertiary,
    error = UiScopeTokens.DarkError,
    onError = UiScopeTokens.DarkOnError,
    background = UiScopeTokens.DarkBackground,
    onBackground = UiScopeTokens.DarkOnBackground,
    surface = UiScopeTokens.DarkSurface,
    onSurface = UiScopeTokens.DarkOnSurface,
    surfaceVariant = UiScopeTokens.DarkSurfaceVariant,
    onSurfaceVariant = UiScopeTokens.DarkOnSurfaceVariant,
    outline = UiScopeTokens.DarkOutline,
)

/** Type and shape tokens: Material 3 defaults with the app's corner scale. */
private val UiScopeTypography = Typography()
private val UiScopeShapes = Shapes(
    extraSmall = UiScopeTokens.cornerSmall,
    small = UiScopeTokens.cornerSmall,
    medium = UiScopeTokens.cornerMedium,
    large = UiScopeTokens.cornerLarge,
    extraLarge = UiScopeTokens.cornerLarge,
)

/** True when [mode] resolves to the dark scheme; SYSTEM follows the OS (re-evaluated on composition). */
@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/** The one theme of the app (plan P4-1): everything renders through it; no inline colour schemes anywhere else. */
@Composable
fun UiScopeTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDarkTheme(mode)) UiScopeDarkColors else UiScopeLightColors,
        typography = UiScopeTypography,
        shapes = UiScopeShapes,
        content = content,
    )
}
