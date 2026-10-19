package com.titanicbhai.uiscope.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary                = AccentBlue,
    onPrimary              = Background,
    primaryContainer       = Color(0xFF1C3A5E),
    onPrimaryContainer     = AccentBlue,
    secondary              = AccentGreen,
    onSecondary            = Background,
    secondaryContainer     = Color(0xFF0F3320),
    onSecondaryContainer   = AccentGreen,
    tertiary               = AccentPurple,
    onTertiary             = Background,
    error                  = AccentRed,
    onError                = Background,
    surface                = Surface,
    onSurface              = OnBackground,
    surfaceVariant         = SurfaceVar,
    onSurfaceVariant       = Muted,
    background             = Background,
    onBackground           = OnBackground,
    outline                = Outline,
    outlineVariant         = Outline.copy(alpha = 0.5f),
    inverseSurface         = OnBackground,
    inverseOnSurface       = Background,
    inversePrimary         = AccentBlue.copy(alpha = 0.7f),
    scrim                  = Color.Black.copy(alpha = 0.4f)
)

private val LightColors = lightColorScheme(
    primary                = Color(0xFF1A6EC7),
    onPrimary              = Color.White,
    primaryContainer       = Color(0xFFD6E4FF),
    onPrimaryContainer     = Color(0xFF001D3D),
    secondary              = Color(0xFF1E8A3A),
    onSecondary            = Color.White,
    error                  = Color(0xFFBA1A1A),
    onError                = Color.White,
    surface                = Color(0xFFF8F9FC),
    onSurface              = Color(0xFF1A1C1E),
    surfaceVariant         = Color(0xFFECEDF1),
    onSurfaceVariant       = Color(0xFF43474E),
    background             = Color(0xFFFFFFFF),
    onBackground           = Color(0xFF1A1C1E),
    outline                = Color(0xFFCDD0D5)
)

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun UiScopeTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
