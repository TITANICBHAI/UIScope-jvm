package com.titanicbhai.uiscope

import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.titanicbhai.uiscope.android.AdbManager
import com.titanicbhai.uiscope.inspector.InspectorScreen
import com.titanicbhai.uiscope.launcher.LauncherScreen
import com.titanicbhai.uiscope.model.InspectionMode
import com.titanicbhai.uiscope.onboarding.OnboardingScreen
import com.titanicbhai.uiscope.repository.SettingsRepository
import com.titanicbhai.uiscope.theme.UiScopeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AppScreen { ONBOARDING, LAUNCHER, INSPECTOR }

fun main() = application {
    val settings = remember { SettingsRepository() }
    val adbManager = remember { AdbManager() }

    var darkTheme by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(AppScreen.LAUNCHER) }
    var mode by remember { mutableStateOf(InspectionMode.PC) }
    var adbAvailable by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val lastMode = settings.get(SettingsRepository.LAST_MODE)
        if (lastMode != null) {
            mode = runCatching { InspectionMode.valueOf(lastMode) }.getOrDefault(InspectionMode.PC)
        }
        val theme = settings.get(SettingsRepository.THEME, "SYSTEM")
        darkTheme = theme == "DARK"

        val savedAdbPath = settings.get(SettingsRepository.ADB_PATH)
        if (!savedAdbPath.isNullOrBlank()) {
            adbManager.setAdbPath(savedAdbPath)
        }

        adbAvailable = withContext(Dispatchers.IO) { adbManager.isAdbAvailable() }

        val onboardingSeen = settings.getBoolean(SettingsRepository.ONBOARDING_SEEN, false)
        screen = if (onboardingSeen) AppScreen.LAUNCHER else AppScreen.ONBOARDING
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "UIScope — See what your UI is made of.",
        state = WindowState(size = DpSize(1280.dp, 820.dp))
    ) {
        UiScopeTheme(darkTheme = darkTheme) {
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
                    }
                )
                AppScreen.INSPECTOR -> InspectorScreen(
                    mode = mode,
                    adbManager = adbManager,
                    onSwitchMode = { newMode ->
                        mode = newMode
                        settings.set(SettingsRepository.LAST_MODE, newMode.name)
                    },
                    onBack = { screen = AppScreen.LAUNCHER }
                )
            }
        }
    }
}
