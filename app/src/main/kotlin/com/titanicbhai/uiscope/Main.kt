package com.titanicbhai.uiscope

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.titanicbhai.uiscope.adb.AdbLocator
import com.titanicbhai.uiscope.adb.EngineAdbPort
import com.titanicbhai.uiscope.adb.SettingsAdbPathStore
import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.logging.LoggingSetup
import com.titanicbhai.uiscope.process.JdkNetworkGateway
import com.titanicbhai.uiscope.process.SystemProcessRunner
import com.titanicbhai.uiscope.snapshot.RetainingSnapshotStore
import com.titanicbhai.uiscope.snapshot.retentionPolicy
import com.titanicbhai.uiscope.state.AndroidInspectState
import com.titanicbhai.uiscope.storage.DataPaths
import com.titanicbhai.uiscope.storage.FileSettingsStore
import com.titanicbhai.uiscope.storage.LegacyData
import com.titanicbhai.uiscope.storage.SnapshotBundles
import com.titanicbhai.uiscope.storage.SnapshotDatabase
import com.titanicbhai.uiscope.storage.SnapshotStoreImpl
import com.titanicbhai.uiscope.update.UpdateChecker
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val shellLogger = Logger.getLogger("uiscope.shell")

/** Reported by [RetainingSnapshotStore] when the post-save cleanup fails; logged with context, never swallowed. */
private fun logRetentionError(error: AppError) {
    shellLogger.log(Level.WARNING, "retention cleanup failed: code={0}, detail={1}", arrayOf(error.code, error.detail))
}

/**
 * Temporary shell (plan P0-2, extended in P2-5 with a developer harness and in the Round 6 wiring with snapshot storage,
 * bundles and the update check — HANDOFF §8.2). The real application shell replaces this in P4-2.
 * Wiring shown here (data folder, logging, settings, process runner, adb locator, state holder, snapshot store) is what
 * the real shell will do too.
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

    application {
        Window(
            onCloseRequest = {
                android.stop()
                settings.flush()
                snapshotsReady.value?.close() // checkpoints the SQLite WAL
                logging?.close()
                exitApplication()
            },
            title = "${appInfo.name}: developer harness",
        ) {
            DevHarness(android, appInfo, paths, snapshotsReady, updater)
        }
    }
}
