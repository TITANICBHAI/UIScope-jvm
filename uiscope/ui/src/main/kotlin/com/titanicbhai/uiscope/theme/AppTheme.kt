package com.titanicbhai.uiscope.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6EABF6),
    onPrimary = Color(0xFF003B6F),
    primaryContainer = Color(0xFF004D92),
    onPrimaryContainer = Color(0xFFD6E4FF),
    surface = Color(0xFF1E2124),
    onSurface = Color(0xFFE2E2E5),
    surfaceVariant = Color(0xFF282B2F),
    onSurfaceVariant = Color(0xFFBFC3C8),
    background = Color(0xFF16181A),
    onBackground = Color(0xFFE2E2E5),
    outline = Color(0xFF3E4246),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A6EC7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001D3D),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFECEDF1),
    onSurfaceVariant = Color(0xFF43474E),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1C1E),
    outline = Color(0xFFCDD0D5),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun UiScopeTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
