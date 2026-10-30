package com.titanicbhai.uiscope

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowSize
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.titanicbhai.uiscope.adb.AdbLocator
import com.titanicbhai.uiscope.adb.EngineAdbPort
import com.titanicbhai.uiscope.adb.SettingsAdbPathStore
import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.logging.LoggingSetup
import com.titanicbhai.uiscope.process.JdkNetworkGateway
import com.titanicbhai.uiscope.process.SystemProcessRunner
import com.titanicbhai.uiscope.ui.shell.AppShell
import com.titanicbhai.uiscope.ui.shell.NoSingleInstance
import com.titanicbhai.uiscope.ui.shell.ShellDialogState
import com.titanicbhai.uiscope.ui.shell.ShellScreen
import com.titanicbhai.uiscope.ui.shell.ShellStatus
import com.titanicbhai.uiscope.ui.shell.SingleInstanceHook
import com.titanicbhai.uiscope.ui.shell.ToastState
import com.titanicbhai.uiscope.ui.shell.WindowPlacementMath
import com.titanicbhai.uiscope.ui.shell.rememberShellDialogState
import com.titanicbhai.uiscope.ui.shell.rememberToastState
import com.titanicbhai.uiscope.snapshot.RetainingSnapshotStore
import com.titanicbhai.uiscope.snapshot.retentionPolicy
import com.titanicbhai.uiscope.state.AndroidInspectState
import com.titanicbhai.uiscope.state.AndroidState
import com.titanicbhai.uiscope.storage.DataPaths
import com.titanicbhai.uiscope.storage.FileSettingsStore
import com.titanicbhai.uiscope.storage.LegacyData
import com.titanicbhai.uiscope.storage.SnapshotBundles
import com.titanicbhai.uiscope.storage.SnapshotDatabase
import com.titanicbhai.uiscope.storage.SnapshotStoreImpl
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import com.titanicbhai.uiscope.update.UpdateChecker
import java.awt.Toolkit
import java.awt.Dimension
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val shellLogger = Logger.getLogger("uiscope.shell")

/** Reported by [RetainingSnapshotStore] when the post-save cleanup fails; logged with context, never swallowed. */
private fun logRetentionError(error: AppError) {
    shellLogger.log(Level.WARNING, "retention cleanup failed: code={0}, detail={1}", arrayOf(error.code, error.detail))
}

/** One line for the status bar's left slot (plan §4.1): what the Android side is doing right now. */
private fun statusTarget(ui: com.titanicbhai.uiscope.state.AndroidInspectUi): String = when (val state = ui.state) {
    AndroidState.Starting -> "looking for adb…"
    AndroidState.AdbMissing -> "adb not found"
    AndroidState.NoDevice -> "no device"
    is AndroidState.ChooseDevice -> "${state.devices.size} devices"
    is AndroidState.Unauthorized -> "device · not authorised"
    is AndroidState.Offline -> "device · offline"
    is AndroidState.NoPermissions -> "device · no permissions"
    is AndroidState.Ready -> state.device.model ?: "device ready"
    is AndroidState.Capturing -> "capturing…"
    is AndroidState.Captured -> state.device.model ?: "captured"
    is AndroidState.CaptureFailed -> "capture failed"
    is AndroidState.Disconnected -> "disconnected"
}

/** Placeholder for screens that arrive with later Phase 4/5 tasks; the shell must never show a blank pane. */
@androidx.compose.runtime.Composable
private fun PlaceholderScreen(name: String, task: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$name is being rebuilt", style = MaterialTheme.typography.titleMedium)
            Text("This screen arrives with task $task.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * The application shell (plan P4-2, §4.1) hosting the developer harness until the real Inspect screen exists (P4-3).
 * Wiring shown here (data folder, logging, settings, process runner, adb locator, state holder, snapshot store, window
 * state) is what the real shell keeps.
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

    // Snapshot storage becomes available a moment after start-up: the database is opened on an I/O thread (HANDOFF §8.2).
    val snapshotsReady = mutableStateOf<SnapshotStoreImpl?>(null)
    val bundles = mutableStateOf<SnapshotBundles?>(null) // held for the Phase 5 screens
    val updater = UpdateChecker(JdkNetworkGateway(dispatchers = dispatchers), appInfo, enabled = { settings.settings.value.checkForUpdates })
    scope.launch(Dispatchers.IO) {
        val database = SnapshotDatabase(paths.database.resolve("snapshots.db"), sqliteTmpDir = paths.tmp)
        val impl = SnapshotStoreImpl(database, paths.snapshots, dispatchers, appVersion = appInfo.version)
        if (impl.open() is Outcome.Failure) {
            shellLogger.warning("snapshots stay unavailable for this session (see the store's log line)")
            return@launch
        }
        val store = RetainingSnapshotStore(impl, { settings.settings.value.retentionPolicy() }, ::logRetentionError)
        store.enforceNow()
        LegacyData(DataPaths.legacyRoot(Path.of(System.getProperty("user.home"))), paths).movePlatformTools().let { moved ->
            if (moved is Outcome.Failure) shellLogger.warning("the old platform-tools folder stays where it was: code=" + moved.error.code)
        }
        bundles.value = SnapshotBundles(store, dispatchers, paths.tmp, appInfo.version)
        snapshotsReady.value = impl
    }

    // Window geometry (plan §4.1, M10): restore from settings, clamped to the screen; minimum 960×600.
    val screen = Toolkit.getDefaultToolkit().screenSize
    val saved = settings.settings.value
    val spec = WindowPlacementMath.restore(saved.windowWidth, saved.windowHeight, saved.windowX, saved.windowY, saved.windowMaximized, screen.width, screen.height)
    val singleInstance: SingleInstanceHook = NoSingleInstance // P7-1 replaces this with the lock-file guard

    application {
        singleInstance.start(onSecondLaunch = { /* P7-1: focus the first window */ })
        val savedX = spec.x
        val savedY = spec.y
        val windowState = rememberWindowState(
            placement = if (spec.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = if (savedX != null && savedY != null) WindowPosition(savedX.dp, savedY.dp) else WindowPosition.PlatformDefault,
            size = WindowSize(spec.width.dp, spec.height.dp),
        )        // Persist size, position and maximized state; the settings store debounces the writes.
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.placement, windowState.size, windowState.position) }.collect {
                settings.update { current ->
                    current.copy(
                        windowWidth = windowState.size.width.value.toInt(),
                        windowHeight = windowState.size.height.value.toInt(),
                        windowX = (windowState.position as? WindowPosition)?.x?.value?.toInt() ?: current.windowX,
                        windowY = (windowState.position as? WindowPosition)?.y?.value?.toInt() ?: current.windowY,
                        windowMaximized = windowState.placement == WindowPlacement.Maximized,
                    )
                }
            }
        }
        val onClose: () -> Unit = {
            android.stop()
            settings.flush()
            snapshotsReady.value?.close()
            logging?.close()
            exitApplication()
        }
        Window(
            onCloseRequest = onClose,
            state = windowState,
            title = "${appInfo.name} ${appInfo.version} — developer harness",
        ) {
            // CMP 1.7 removed Window's minWidth/minHeight parameters; the minimum is enforced on the AWT window instead,
            // in real pixels for the current density.
            val density = LocalDensity.current
            LaunchedEffect(density) {
                window.minimumSize = Dimension(
                    with(density) { WindowPlacementMath.MIN_WIDTH.dp.toPx().roundToInt() },
                    with(density) { WindowPlacementMath.MIN_HEIGHT.dp.toPx().roundToInt() },
                )
            }
            // The menu bar mirrors actions (plan §4.1); View and Help entries arrive with their screens (P4-5/P4-7).
            MenuBar {
                Menu("File", mnemonic = 'F') {
                    Item("Exit", onClick = onClose)
                }
                Menu("View", mnemonic = 'V') {
                    Item("Fit", enabled = false, onClick = {})
                    Item("Actual size", enabled = false, onClick = {})
                    Item("Show all bounds", enabled = false, onClick = {})
                    Item("Live", enabled = false, onClick = {})
                }
                Menu("Help", mnemonic = 'H') {
                    Item("Welcome", enabled = false, onClick = {})
                    Item("Keyboard shortcuts", enabled = false, onClick = {})
                    Item("Report an issue", enabled = false, onClick = {})
                    Item("About", enabled = false, onClick = {})
                }
            }
            val settingsState by settings.settings.collectAsState()
            val inspect by android.ui.collectAsState()
            var screen by remember { mutableStateOf(ShellScreen.INSPECT) }
            val toasts = rememberToastState()
            val dialogs = rememberShellDialogState()
            val status = ShellStatus(
                target = statusTarget(inspect),
                captureInfo = inspect.capture?.let { "${inspect.index?.size ?: 0} elements" },
                warning = inspect.problem,
                live = inspect.live.enabled,
            )
            UiScopeTheme(mode = settingsState.theme) {
                AppShell(
                    screen = screen,
                    onScreen = { screen = it },
                    status = status,
                    toasts = toasts,
                    dialogs = dialogs,
                ) {
                    when (screen) {
                        ShellScreen.INSPECT -> DevHarness(android, appInfo, paths, snapshotsReady, updater)
                        ShellScreen.SNAPSHOTS -> PlaceholderScreen("Snapshots", "P5-1")
                        ShellScreen.SETTINGS -> PlaceholderScreen("Settings", "P4-8")
                        ShellScreen.HELP -> PlaceholderScreen("Help", "P4-7")
                    }
                }
            }
        }
    }
}
