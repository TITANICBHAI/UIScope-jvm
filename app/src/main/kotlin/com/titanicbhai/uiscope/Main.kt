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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.titanicbhai.uiscope.adb.AdbDownloader
import com.titanicbhai.uiscope.adb.AdbLocator
import com.titanicbhai.uiscope.adb.EngineAdbPort
import com.titanicbhai.uiscope.adb.PlatformToolsManifestData
import com.titanicbhai.uiscope.adb.SettingsAdbPathStore
import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.error.map
import com.titanicbhai.uiscope.logging.LoggingSetup
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.process.JdkNetworkGateway
import com.titanicbhai.uiscope.process.Relauncher
import com.titanicbhai.uiscope.process.SystemProcessLauncher
import com.titanicbhai.uiscope.process.SystemProcessRunner
import com.titanicbhai.uiscope.settings.InspectTarget
import com.titanicbhai.uiscope.ui.firstrun.AdbDownloadActions
import com.titanicbhai.uiscope.ui.firstrun.FirstRunCallbacks
import com.titanicbhai.uiscope.ui.firstrun.FirstRunScreen
import com.titanicbhai.uiscope.ui.help.HelpScreen
import com.titanicbhai.uiscope.ui.inspect.ExportActions
import com.titanicbhai.uiscope.ui.inspect.ExportDialog
import com.titanicbhai.uiscope.ui.inspect.InspectCallbacks
import com.titanicbhai.uiscope.ui.inspect.InspectScreen
import com.titanicbhai.uiscope.ui.inspect.SnapshotInspector
import com.titanicbhai.uiscope.ui.inspect.WindowsCallbacks
import com.titanicbhai.uiscope.ui.snapshots.SnapshotCallbacks
import com.titanicbhai.uiscope.ui.snapshots.SnapshotsScreen
import com.titanicbhai.uiscope.ui.settings.SettingsScreen
import com.titanicbhai.uiscope.ui.shell.AppShell
import com.titanicbhai.uiscope.update.UpdateCheck
import com.titanicbhai.uiscope.ui.shell.ShellDialogState
import com.titanicbhai.uiscope.ui.shell.ShellScreen
import com.titanicbhai.uiscope.ui.shell.ShellStatus
import com.titanicbhai.uiscope.ui.shell.LockFileSingleInstance
import com.titanicbhai.uiscope.ui.shell.SingleInstanceHook
import com.titanicbhai.uiscope.ui.shell.ToastState
import com.titanicbhai.uiscope.ui.shell.WindowPlacementMath
import com.titanicbhai.uiscope.ui.shell.rememberShellDialogState
import com.titanicbhai.uiscope.ui.shell.rememberToastState
import com.titanicbhai.uiscope.snapshot.LoadedSnapshot
import com.titanicbhai.uiscope.snapshot.RetainingSnapshotStore
import com.titanicbhai.uiscope.snapshot.retentionPolicy
import com.titanicbhai.uiscope.state.AndroidInspectState
import com.titanicbhai.uiscope.state.AndroidState
import com.titanicbhai.uiscope.state.WindowsEvent
import com.titanicbhai.uiscope.state.WindowsInspectState
import com.titanicbhai.uiscope.state.WindowsInspectUi
import com.titanicbhai.uiscope.state.WindowsState
import com.titanicbhai.uiscope.windows.EngineWindowsPort
import com.titanicbhai.uiscope.windows.WindowsHelperClient
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
import kotlinx.coroutines.withContext

private val shellLogger = Logger.getLogger("uiscope.shell")

/** The native file dialog (parented, so never the 1.0 bug of a parentless chooser); the choice lands in settings and the engines re-check. */
private fun browseForAdb(window: java.awt.Frame, settings: com.titanicbhai.uiscope.settings.SettingsStore, toasts: com.titanicbhai.uiscope.ui.shell.ToastState, onRechecked: () -> Unit) {
    val dialog = java.awt.FileDialog(window, "Choose the adb executable", java.awt.FileDialog.LOAD)
    dialog.isVisible = true
    val chosen = dialog.file?.let { Path.of(dialog.directory, it) } ?: return
    settings.update { it.copy(adbPath = chosen.toString()) }
    onRechecked()
}

/** Reported by [RetainingSnapshotStore] when the post-save cleanup fails; logged with context, never swallowed. */
private fun logRetentionError(error: AppError) {
    shellLogger.log(Level.WARNING, "retention cleanup failed: code={0}, detail={1}", arrayOf(error.code, error.detail))
}

private val exportLogger = Logger.getLogger("uiscope.export")

/** The native Save dialog on the event thread (same pattern as the adb browser); null means the user cancelled. */
private fun chooseSaveFile(window: java.awt.Frame, suggestedName: String): Path? {
    val dialog = java.awt.FileDialog(window, "Export", java.awt.FileDialog.SAVE)
    dialog.file = suggestedName
    dialog.isVisible = true
    return dialog.file?.let { Path.of(dialog.directory, it) }
}

private fun chooseOpenFile(window: java.awt.Frame): Path? {
    val dialog = java.awt.FileDialog(window, "Import a .uiscope bundle", java.awt.FileDialog.LOAD)
    dialog.isVisible = true
    return dialog.file?.let { Path.of(dialog.directory, it) }
}

/** The PNG export (4.8): the screenshot with the selected element outlined, origin subtracted (P3-5). */
private suspend fun renderPngWithOutline(capture: Capture, index: ElementIndex?, selected: ElementPath?): ByteArray? = withContext(Dispatchers.IO) {
    val shot = capture.screenshot ?: return@withContext null
    val image = try {
        javax.imageio.ImageIO.read(java.io.File(shot.file))
    } catch (t: Throwable) {
        exportLogger.warning("the screenshot could not be read for the PNG export: " + t.javaClass.simpleName)
        return@withContext null
    }
    val node = selected?.let { index?.byPath?.get(it) }
    if (node != null) {
        val graphics = image.createGraphics()
        graphics.color = java.awt.Color(230, 57, 70)
        graphics.stroke = java.awt.BasicStroke(3f)
        graphics.drawRect(
            node.bounds.x - capture.screen.originX,
            node.bounds.y - capture.screen.originY,
            node.bounds.width,
            node.bounds.height,
        )
        graphics.dispose()
    }
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(image, "png", out)
    out.toByteArray()
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

/** One line for the status bar's left slot: what the Windows side is doing right now. */
private fun windowsStatusLine(ui: WindowsInspectUi): String = when (val state = ui.state) {
    WindowsState.Starting -> "checking the helper..."
    is WindowsState.HelperMissing -> "helper unavailable"
    WindowsState.Idle -> "choose a window"
    WindowsState.Picking -> "picking..."
    is WindowsState.Capturing -> "capturing..."
    is WindowsState.Captured -> state.window.title
    is WindowsState.CaptureFailed -> "capture failed"
    is WindowsState.WindowGone -> "window closed"
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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashHandling.coroutineHandler())
    // P7-2: fatal crashes log and show the Copy-diagnostics/Restart dialog; coroutine failures are logged only.
    CrashHandling.install(
        diagnostics = {
            buildString {
                appendLine("${appInfo.name} ${appInfo.version} (build ${appInfo.buildDate}, ${appInfo.commit})")
                appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
                appendLine("Java: ${System.getProperty("java.version")}")
                appendLine("Data folder: ${paths.root}")
            }
        },
        relaunch = {
            val java = Path.of(System.getProperty("java.home"), "bin", if (HostOs.current() == HostOs.WINDOWS) "java.exe" else "java").toString()
            when (val relaunchResult = Relauncher.relaunch(listOf(java, "-cp", System.getProperty("java.class.path"), "com.titanicbhai.uiscope.MainKt"))) {
                is Outcome.Success -> kotlin.system.exitProcess(0)
                is Outcome.Failure -> shellLogger.warning("the restart could not be spawned: " + relaunchResult.error.code)
            }
        },
    )
    val settings = FileSettingsStore(paths.settingsFile, scope, dispatchers)
    val runner = SystemProcessRunner(dispatchers)
    val locator = AdbLocator(runner, SettingsAdbPathStore(settings), paths, HostOs.current(), System.getenv(), Path.of(System.getProperty("user.home")))
    val android = AndroidInspectState(EngineAdbPort(locator, runner, paths, dispatchers), settings, dispatchers, scope)
    android.start()

    // The Windows helper (P3-1/P3-2): the exe is extracted next to the data folder, then the state's start()
    // self-tests it with listWindows - a failure lands in HelperMissing and the UI says so honestly.
    val windows: WindowsInspectState? = if (FeatureFlags.forHost().windowsPc) {
        val helperPath = WindowsHelperExe.extract(paths) ?: paths.root.resolve("helper").resolve("UIScope.Helper.exe")
        val helperClient = WindowsHelperClient(
            launcher = SystemProcessLauncher(dispatchers),
            helperCommand = listOf(helperPath.toString()),
            dispatchers = dispatchers,
        )
        WindowsInspectState(EngineWindowsPort(helperClient, dispatchers = dispatchers), dispatchers, scope).also { it.start() }
    } else {
        null
    }

    // Snapshot storage becomes available a moment after start-up: the database is opened on an I/O thread (HANDOFF §8.2).
    val snapshotsReady = mutableStateOf<SnapshotStoreImpl?>(null)
    val bundles = mutableStateOf<SnapshotBundles?>(null) // held for the Phase 5 screens
    val updater = UpdateChecker(JdkNetworkGateway(dispatchers = dispatchers), appInfo, enabled = { settings.settings.value.checkForUpdates })
    // The ADB download (P2-4): the consent text and the install are wired here, the first-run and Settings screens only see lambdas.
    val adbDownloader = AdbDownloader(JdkNetworkGateway(dispatchers = dispatchers), paths, PlatformToolsManifestData.MANIFEST, HostOs.current(), dispatchers)
    val adbDownload = AdbDownloadActions(
        consentText = { adbDownloader.consent().map { it.text() } },
        install = { onProgress -> adbDownloader.install(onProgress) },
    )
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
    val singleInstance = LockFileSingleInstance(
        lockFile = paths.root.resolve("single-instance.lock"),
        focusFile = paths.root.resolve("single-instance.focus"),
    )

    application {
        singleInstance.start(onSecondLaunch = { java.awt.Window.getWindows().filter { it.isVisible }.forEach { it.toFront() } })
        val savedX = spec.x
        val savedY = spec.y
        val windowState = rememberWindowState(
            placement = if (spec.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = if (savedX != null && savedY != null) WindowPosition(savedX.dp, savedY.dp) else WindowPosition.PlatformDefault,
            size = DpSize(spec.width.dp, spec.height.dp),
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
            singleInstance.release()
            windows?.stop()
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
            // The menu bar mirrors actions (plan §4.1); View entries arrive with their screens (P4-5).
            val settingsState by settings.settings.collectAsState()
            val inspect by android.ui.collectAsState()
            val windowsInspect = windows?.ui?.collectAsState()?.value
            val toasts = rememberToastState()
            val dialogs = rememberShellDialogState()
            LaunchedEffect(windows) {
                windows?.events?.collect { event ->
                    when (event) {
                        WindowsEvent.PickCancelled -> toasts.show("Pick cancelled")
                        WindowsEvent.SelectionLost -> toasts.show("The selected element is gone after the refresh")
                    }
                }
            }
            if (!settingsState.firstRunDone) {
                UiScopeTheme(mode = settingsState.theme) {
                    FirstRunScreen(
                        ui = inspect,
                        target = settingsState.lastTarget,
                        windowsPcAvailable = FeatureFlags.forHost().windowsPc,
                        storeBuild = appInfo.isStoreBuild,
                        checkForUpdates = settingsState.checkForUpdates,
                        download = adbDownload,
                        callbacks = FirstRunCallbacks(
                            onRecheck = { android.start() },
                            onSetTarget = { t -> settings.update { it.copy(lastTarget = t) } },
                            onSetCheckUpdates = { enabled -> settings.update { it.copy(checkForUpdates = enabled) } },
                            onBrowseAdb = { browseForAdb(window, settings, toasts) { android.start() } },
                            onFinish = { settings.update { it.copy(firstRunDone = true) } },
                        ),
                    )
                }
                return@Window
            }
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
                    Item("Check for updates", onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { updater.check() }
                            when (result) {
                                is UpdateCheck.Available -> toasts.show("Update available: v${result.version}")
                                UpdateCheck.UpToDate -> toasts.show("UIScope is up to date")
                                UpdateCheck.Disabled -> Unit
                                is UpdateCheck.Failed -> Unit // logged by the checker, never shown (P7-5)
                            }
                        }
                    })
                    Item("Welcome", onClick = { settings.update { it.copy(firstRunDone = false) } })
                    Item("Keyboard shortcuts", enabled = false, onClick = {})
                    Item("Report an issue", enabled = false, onClick = {})
                    Item("About", enabled = false, onClick = {})
                }
            }
            var screen by remember { mutableStateOf(ShellScreen.INSPECT) }
            var exportOpen by remember { mutableStateOf(false) }
            val openSnapshot = remember { mutableStateOf<LoadedSnapshot?>(null) }
            val status = if (settingsState.lastTarget == InspectTarget.WINDOWS && windowsInspect != null) {
                ShellStatus(
                    target = windowsStatusLine(windowsInspect),
                    captureInfo = windowsInspect.capture?.let { "${windowsInspect.index?.size ?: 0} elements" },
                    warning = windowsInspect.problem,
                    live = false,
                )
            } else {
                ShellStatus(
                    target = statusTarget(inspect),
                    captureInfo = inspect.capture?.let { "${inspect.index?.size ?: 0} elements" },
                    warning = inspect.problem,
                    live = inspect.live.enabled,
                )
            }
            UiScopeTheme(mode = settingsState.theme) {
                AppShell(
                    screen = screen,
                    onScreen = { screen = it },
                    status = status,
                    toasts = toasts,
                    dialogs = dialogs,
                ) {
                    when (screen) {
                        ShellScreen.INSPECT -> {
                            val snap = openSnapshot.value
                            if (snap != null) {
                                SnapshotInspector(loaded = snap, onClose = { openSnapshot.value = null })
                            } else {
                                InspectScreen(
                            ui = inspect,
                            target = settingsState.lastTarget,
                            windowsPcAvailable = FeatureFlags.forHost().windowsPc,
                            callbacks = InspectCallbacks(
                                onStart = { android.start() },
                                onSelectDevice = { android.selectDevice(it) },
                                onCapture = { android.capture() },
                                onCancelCapture = { android.cancelCapture() },
                                onSetLive = { android.setLive(it) },
                                onSelect = { android.select(it) },
                                onToggleExpanded = { android.toggleExpanded(it) },
                                onExpandAll = { android.expandAll() },
                                onCollapseAll = { android.collapseAll() },
                                onExpandSubtree = { android.expandSubtree(it) },
                                onCollapseSubtree = { android.collapseSubtree(it) },
                                onSetTarget = {
                                    settings.update { current -> current.copy(lastTarget = it) }
                                },
                                onSetTreePaneWidth = { width ->
                                    settings.update { current -> current.copy(treePaneWidth = width) }
                                },
                                onSetDetailsPaneWidth = { width ->
                                    settings.update { current -> current.copy(detailsPaneWidth = width) }
                                },
                                onSaveSnapshot = {
                                    scope.launch {
                                        val cap = if (settingsState.lastTarget == InspectTarget.WINDOWS) windowsInspect?.capture else inspect.capture
                                        val store = snapshotsReady.value
                                        if (cap == null || store == null) {
                                            toasts.showError("Capture something first.")
                                        } else {
                                            when (val saved = store.save(cap)) {
                                                is Outcome.Success -> toasts.show("Snapshot saved.")
                                                is Outcome.Failure -> toasts.showError("Could not save the snapshot (${saved.error.code}).")
                                            }
                                        }
                                    }
                                },
                                onExport = { exportOpen = true },
                            ),
                            windows = windowsInspect,
                            windowsCallbacks = WindowsCallbacks(
                                onStart = { windows?.start() },
                                onSelectWindow = { hwnd -> windows?.chooseWindow(hwnd) },
                                onPick = { windows?.startPick() },
                                onCancelPick = { windows?.cancelPick() },
                                onRefresh = { windows?.refresh() },
                                onSelect = { windows?.select(it) },
                                onToggleExpanded = { windows?.toggleExpanded(it) },
                                onExpandAll = { windows?.expandAll() },
                                onCollapseAll = { windows?.collapseAll() },
                                onExpandSubtree = { windows?.expandSubtree(it) },
                                onCollapseSubtree = { windows?.collapseSubtree(it) },
                                onSetTreePaneWidth = { width ->
                                    settings.update { current -> current.copy(treePaneWidth = width) }
                                },
                                onSetDetailsPaneWidth = { width ->
                                    settings.update { current -> current.copy(detailsPaneWidth = width) }
                                },
                            ),
                                treePaneWidth = settingsState.treePaneWidth,
                                detailsPaneWidth = settingsState.detailsPaneWidth,
                            )
                            }
                        }
                        ShellScreen.SNAPSHOTS -> SnapshotsScreen(
                            store = snapshotsReady.value,
                            callbacks = SnapshotCallbacks(
                                onMessage = { toasts.show(it) },
                                onOpenInInspector = { loaded ->
                                    openSnapshot.value = loaded
                                    screen = ShellScreen.INSPECT
                                },
                                exportBundle = { id, target ->
                                    bundles.value?.export(id, target)
                                        ?: Outcome.Failure(CodedError(ErrorCodes.UNEXPECTED, "bundles are not ready"))
                                },
                                importBundle = { source ->
                                    bundles.value?.import(source)
                                        ?: Outcome.Failure(CodedError(ErrorCodes.UNEXPECTED, "bundles are not ready"))
                                },
                                pickExportFile = { name -> chooseSaveFile(window, name) },
                                pickImportFile = { chooseOpenFile(window) },
                            ),
                        )
                        ShellScreen.SETTINGS -> SettingsScreen(
                            settings = settings,
                            appInfo = appInfo,
                            adbLine = inspect.adb?.let { "${it.path} (version ${it.version}, found via ${it.source})" },
                            snapshots = snapshotsReady.value,
                            adbDownload = adbDownload,
                            onBrowseAdb = { browseForAdb(window, settings, toasts) { android.start() } },
                            onAdbRecheck = { android.start() },
                            diagnostics = {
                                buildString {
                                    appendLine("${appInfo.name} ${appInfo.version} (build ${appInfo.buildDate}, ${appInfo.commit})")
                                    appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
                                    appendLine("Java: ${System.getProperty("java.version")}")
                                    appendLine("Data folder: ${paths.root}")
                                    appendLine("adb: ${inspect.adb?.let { "${it.path} (${it.version}, via ${it.source})" } ?: "not found"}")
                                    appendLine("Devices: ${inspect.devices.size}")
                                }
                            },
                            onOpenLogs = {
                                try {
                                    java.awt.Desktop.getDesktop().open(paths.logs.toFile())
                                } catch (t: Throwable) {
                                    toasts.showError("Could not open the folder (${t.javaClass.simpleName})")
                                }
                            },
                            onOpenSnapshots = {
                                try {
                                    java.awt.Desktop.getDesktop().open(paths.snapshots.toFile())
                                } catch (t: Throwable) {
                                    toasts.showError("Could not open the folder (${t.javaClass.simpleName})")
                                }
                            },
                        )
                        ShellScreen.HELP -> HelpScreen(appInfo = appInfo, onReplayFirstRun = { settings.update { it.copy(firstRunDone = false) } })
                    }
                }
                if (exportOpen) {
                    val fromWindows = settingsState.lastTarget == InspectTarget.WINDOWS
                    val cap = if (fromWindows) windowsInspect?.capture else inspect.capture
                    if (cap != null) {
                        ExportDialog(
                            capture = cap,
                            index = if (fromWindows) windowsInspect?.index else inspect.index,
                            selected = if (fromWindows) windowsInspect?.selected else inspect.selected,
                            defaultFormat = settingsState.defaultExportFormat,
                            appVersion = appInfo.version,
                            actions = ExportActions(
                                saveAs = { name, bytes ->
                                    val target = withContext(Dispatchers.Main) { chooseSaveFile(window, name) }
                                    if (target == null) {
                                        Outcome.Success(Unit) // the user cancelled the dialog: neither an error nor a message
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            try {
                                                java.nio.file.Files.write(target, bytes)
                                                Outcome.Success(Unit)
                                            } catch (t: Throwable) {
                                                exportLogger.warning("the export could not be written: " + t.javaClass.simpleName)
                                                Outcome.Failure(CodedError(ErrorCodes.UNEXPECTED, t.message))
                                            }
                                        }
                                    }
                                },
                                renderPng = { c, sel -> renderPngWithOutline(c, if (fromWindows) windowsInspect?.index else inspect.index, sel) },
                            ),
                            onDismiss = { exportOpen = false },
                            onMessage = { toasts.show(it) },
                        )
                    }
                }
            }
        }
    }
}
