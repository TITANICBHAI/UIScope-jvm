package com.titanicbhai.uiscope

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.titanicbhai.uiscope.adb.AdbLocator
import com.titanicbhai.uiscope.adb.EngineAdbPort
import com.titanicbhai.uiscope.adb.SettingsAdbPathStore
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.logging.LoggingSetup
import com.titanicbhai.uiscope.process.JdkNetworkGateway
import com.titanicbhai.uiscope.process.SystemProcessRunner
import com.titanicbhai.uiscope.state.AndroidInspectState
import com.titanicbhai.uiscope.storage.DataPaths
import com.titanicbhai.uiscope.storage.FileSettingsStore
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Temporary shell (plan P0-2, extended in P2-5 with a developer harness). The real application shell replaces this in P4-2.
 * Wiring shown here (data folder, logging, settings, process runner, adb locator, state holder) is what the real shell will do too.
 */
fun main() {
    JdkNetworkGateway.enableSystemProxies() // before any network use
    val appInfo = AppInfoLoader.load()
    val paths = DataPaths.forCurrentUser()
    paths.ensureDirectories()
    val logging = (LoggingSetup.install(paths.logs) as? Outcome.Success)?.value
    val dispatchers = StandardDispatcherProvider(Dispatchers.Main)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val settings = FileSettingsStore(paths.settingsFile, scope, dispatchers)
    val runner = SystemProcessRunner(dispatchers)
    val locator = AdbLocator(runner, SettingsAdbPathStore(settings), paths, HostOs.current(), System.getenv(), Path.of(System.getProperty("user.home")))
    val android = AndroidInspectState(EngineAdbPort(locator, runner, paths, dispatchers), settings, dispatchers, scope)
    android.start()
    application {
        Window(
            onCloseRequest = {
                android.stop()
                settings.flush()
                logging?.close()
                exitApplication()
            },
            title = "${appInfo.name}: developer harness",
        ) {
            DevHarness(android, appInfo, paths)
        }
    }
}
