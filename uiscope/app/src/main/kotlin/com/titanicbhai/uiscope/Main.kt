package com.titanicbhai.uiscope

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import com.titanicbhai.uiscope.android.AdbManager
import com.titanicbhai.uiscope.diff.DiffScreen
import com.titanicbhai.uiscope.history.HistoryScreen
import com.titanicbhai.uiscope.inspector.InspectorScreen
import com.titanicbhai.uiscope.launcher.LauncherScreen
import com.titanicbhai.uiscope.model.InspectionMode
import com.titanicbhai.uiscope.onboarding.OnboardingScreen
import com.titanicbhai.uiscope.repository.SettingsRepository
import com.titanicbhai.uiscope.settings.SettingsScreen
import com.titanicbhai.uiscope.theme.AccentBlue
import com.titanicbhai.uiscope.watch.WatchScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BasicStroke
import java.awt.Desktop
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.net.URI
import java.net.URL

enum class AppScreen { ONBOARDING, LAUNCHER, INSPECTOR, HISTORY, SETTINGS, DIFF, WATCH }

private const val APP_VERSION = "1.0.0"
private const val GITHUB_RELEASES_API =
    "https://api.github.com/repos/TITANICBHAI/UIScope/releases/latest"

/** Builds the tray icon programmatically — a crosshair inside a blue rounded square. */
private fun buildTrayIcon(): ImageBitmap {
    val size = 64
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    // Blue rounded square background
    g.color = java.awt.Color(0x58, 0xA6, 0xFF)
    g.fillRoundRect(2, 2, size - 4, size - 4, 14, 14)

    // White crosshair
    g.color = java.awt.Color.WHITE
    g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    val cx = size / 2; val cy = size / 2; val arm = 14; val gap = 7
    g.drawLine(cx - arm, cy, cx - gap, cy)
    g.drawLine(cx + gap, cy, cx + arm, cy)
    g.drawLine(cx, cy - arm, cx, cy - gap)
    g.drawLine(cx, cy + gap, cx, cy + arm)
    g.stroke = BasicStroke(3f)
    g.drawOval(cx - 5, cy - 5, 10, 10)

    g.dispose()
    return img.toComposeImageBitmap()
}

fun main() = application {
    val settings = remember { SettingsRepository() }
    val adbManager = remember { AdbManager() }

    var darkTheme by remember { mutableStateOf(true) }
    var screen by remember { mutableStateOf(AppScreen.LAUNCHER) }
    var previousScreen by remember { mutableStateOf(AppScreen.LAUNCHER) }
    var mode by remember { mutableStateOf(InspectionMode.PC) }
    var adbAvailable by remember { mutableStateOf(false) }
    var updateVersion by remember { mutableStateOf<String?>(null) }
    var windowVisible by remember { mutableStateOf(true) }
    var minimizeToTray by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val lastMode = settings.get(SettingsRepository.LAST_MODE)
        if (lastMode != null) {
            mode = runCatching { InspectionMode.valueOf(lastMode) }.getOrDefault(InspectionMode.PC)
        }
        val theme = settings.get(SettingsRepository.THEME, "DARK")
        darkTheme = theme != "LIGHT"

        val savedAdbPath = settings.get(SettingsRepository.ADB_PATH)
        if (!savedAdbPath.isNullOrBlank()) {
            adbManager.setAdbPath(savedAdbPath)
        }

        adbAvailable = withContext(Dispatchers.IO) { adbManager.isAdbAvailable() }
        minimizeToTray = settings.getBoolean("minimize_to_tray", true)

        val onboardingSeen = settings.getBoolean(SettingsRepository.ONBOARDING_SEEN, false)
        screen = if (onboardingSeen) AppScreen.LAUNCHER else AppScreen.ONBOARDING

        // Silent auto-update check (Plan §21)
        updateVersion = withContext(Dispatchers.IO) { checkForUpdate() }
    }

    // System tray (Plan §19) — visible only when the main window is hidden
    val trayIcon = remember { buildTrayIcon() }
    val trayState = rememberTrayState()

    if (minimizeToTray && !windowVisible) {
        Tray(
            icon = BitmapPainter(trayIcon),
            state = trayState,
            tooltip = "UIScope — See what your UI is made of.",
            menu = {
                Item("Open UIScope", onClick = { windowVisible = true })
                Separator()
                Item(
                    "Quick Pick (${mode.name} mode)",
                    onClick = {
                        windowVisible = true
                        screen = AppScreen.INSPECTOR
                    }
                )
                Separator()
                Item(
                    if (mode == InspectionMode.PC) "Switch to Android Mode" else "Switch to PC Mode",
                    onClick = {
                        mode = if (mode == InspectionMode.PC) InspectionMode.ANDROID else InspectionMode.PC
                        settings.set(SettingsRepository.LAST_MODE, mode.name)
                        windowVisible = true
                        screen = AppScreen.INSPECTOR
                    }
                )
                Separator()
                Item("Quit UIScope", onClick = ::exitApplication)
            }
        )
    }

    Window(
        onCloseRequest = {
            if (minimizeToTray) {
                windowVisible = false
            } else {
                exitApplication()
            }
        },
        visible = windowVisible,
        title = "UIScope — See what your UI is made of.",
        state = WindowState(size = DpSize(1280.dp, 820.dp))
    ) {
        UiScopeTheme(darkTheme = darkTheme) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (screen) {
                    AppScreen.ONBOARDING -> OnboardingScreen(
                        adbAvailable = adbAvailable,
                        onContinue = {
                            settings.setBoolean(SettingsRepository.ONBOARDING_SEEN, true)
                            screen = AppScreen.LAUNCHER
                        }
                    )
                    AppScreen.LAUNCHER -> LauncherScreen(
                        onModeSelected = { selected ->
                            mode = selected
                            settings.set(SettingsRepository.LAST_MODE, selected.name)
                            screen = AppScreen.INSPECTOR
                        },
                        onHistory = {
                            previousScreen = AppScreen.LAUNCHER
                            screen = AppScreen.HISTORY
                        },
                        onSettings = {
                            previousScreen = AppScreen.LAUNCHER
                            screen = AppScreen.SETTINGS
                        }
                    )
                    AppScreen.INSPECTOR -> InspectorScreen(
                        mode = mode,
                        adbManager = adbManager,
                        onSwitchMode = { newMode ->
                            mode = newMode
                            settings.set(SettingsRepository.LAST_MODE, newMode.name)
                        },
                        onBack = { screen = AppScreen.LAUNCHER },
                        onHistory = {
                            previousScreen = AppScreen.INSPECTOR
                            screen = AppScreen.HISTORY
                        },
                        onSettings = {
                            previousScreen = AppScreen.INSPECTOR
                            screen = AppScreen.SETTINGS
                        },
                        onDiff = {
                            previousScreen = AppScreen.INSPECTOR
                            screen = AppScreen.DIFF
                        },
                        onWatch = {
                            previousScreen = AppScreen.INSPECTOR
                            screen = AppScreen.WATCH
                        }
                    )
                    AppScreen.HISTORY -> HistoryScreen(
                        onBack = { screen = previousScreen }
                    )
                    AppScreen.SETTINGS -> SettingsScreen(
                        onBack = {
                            val theme2 = settings.get(SettingsRepository.THEME, "DARK")
                            darkTheme = theme2 != "LIGHT"
                            val savedPath = settings.get(SettingsRepository.ADB_PATH)
                            if (!savedPath.isNullOrBlank()) adbManager.setAdbPath(savedPath)
                            minimizeToTray = settings.getBoolean("minimize_to_tray", true)
                            screen = previousScreen
                        }
                    )
                    AppScreen.DIFF -> DiffScreen(
                        onBack = { screen = previousScreen }
                    )
                    AppScreen.WATCH -> WatchScreen(
                        onBack = { screen = previousScreen }
                    )
                }

                // Update available banner (Plan §21)
                AnimatedVisibility(
                    visible = updateVersion != null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it }
                ) {
                    updateVersion?.let { ver ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AccentBlue.copy(alpha = 0.12f))
                                .clickable {
                                    try {
                                        Desktop.getDesktop().browse(
                                            URI("https://github.com/TITANICBHAI/UIScope/releases")
                                        )
                                    } catch (_: Exception) {}
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Update available: $ver  —  Click to open GitHub Releases",
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentBlue,
                                fontSize = 12.sp
                            )
                            TextButton(
                                onClick = { updateVersion = null },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("✕", color = AccentBlue, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun checkForUpdate(): String? {
    return try {
        val conn = URL(GITHUB_RELEASES_API).openConnection()
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        val json = conn.getInputStream().bufferedReader().readText()
        val tagMatch = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(json)
        val latestTag = tagMatch?.groupValues?.get(1)?.trimStart('v') ?: return null
        if (latestTag != APP_VERSION && latestTag.isNotBlank()) latestTag else null
    } catch (_: Exception) {
        null
    }
}
