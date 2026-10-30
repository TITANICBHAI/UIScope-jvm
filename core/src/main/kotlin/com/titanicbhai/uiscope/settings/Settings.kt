package com.titanicbhai.uiscope.settings

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** "System" follows the operating system's dark flag (it used to behave as Dark: B12). */
    fun isDark(osIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> osIsDark
        LIGHT -> false
        DARK -> true
    }
}

enum class InspectTarget { ANDROID, WINDOWS }

enum class Renderer { AUTO, SOFTWARE }

enum class LogLevelSetting { WARNING, INFO, DEBUG }

enum class ExportFormat { JSON, XML, OUTLINE }

/**
 * Every user setting, with its default, in one place (plan §3.7). No raw string keys exist outside the store.
 * Use [sanitized] after reading untrusted values: it clamps numbers into their allowed range.
 */
data class Settings(
    // General
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val checkForUpdates: Boolean = true,
    val defaultExportFormat: ExportFormat = ExportFormat.JSON,
    val lastFolder: String? = null,
    // Inspect
    val lastTarget: InspectTarget = InspectTarget.ANDROID,
    // Android
    val adbPath: String? = null,
    val lastSerial: String? = null,
    val lastWirelessHost: String? = null,
    val liveIntervalSeconds: Int = DEFAULT_LIVE_SECONDS,
    // Windows
    val pickHotkeyEnabled: Boolean = true,
    val pickHotkey: String = DEFAULT_PICK_HOTKEY,
    val hideWhilePicking: Boolean = true,
    // Snapshots
    val autoSaveSnapshots: Boolean = false,
    val retentionCount: Int = DEFAULT_RETENTION_COUNT,
    val retentionBytes: Long = DEFAULT_RETENTION_BYTES,
    // Advanced
    val renderer: Renderer = Renderer.AUTO,
    val logLevel: LogLevelSetting = LogLevelSetting.INFO,
    // Window (restored at start, clamped to a visible monitor by the shell)
    val windowWidth: Int? = null,
    val windowHeight: Int? = null,
    val windowX: Int? = null,
    val windowY: Int? = null,
    val windowMaximized: Boolean = false,
    /** Width of the Inspect screen's tree pane in dp (P4-3); null = the default split. */
    val treePaneWidth: Int? = null,
    /** Width of the Inspect screen's details pane in dp (P4-3); null = the default split. */
    val detailsPaneWidth: Int? = null,
    /** The first-run screen (P4-7) has been finished or skipped. */
    val firstRunDone: Boolean = false,
) {
    fun sanitized(): Settings = copy(
        liveIntervalSeconds = liveIntervalSeconds.coerceIn(MIN_LIVE_SECONDS, MAX_LIVE_SECONDS),
        retentionCount = retentionCount.coerceAtLeast(MIN_RETENTION_COUNT),
        retentionBytes = retentionBytes.coerceAtLeast(MIN_RETENTION_BYTES),
        pickHotkey = pickHotkey.ifBlank { DEFAULT_PICK_HOTKEY },
        adbPath = adbPath?.ifBlank { null },
        lastSerial = lastSerial?.ifBlank { null },
        lastWirelessHost = lastWirelessHost?.ifBlank { null },
        lastFolder = lastFolder?.ifBlank { null },
        treePaneWidth = treePaneWidth?.coerceIn(MIN_PANE_WIDTH, MAX_PANE_WIDTH),
        detailsPaneWidth = detailsPaneWidth?.coerceIn(MIN_PANE_WIDTH, MAX_PANE_WIDTH),
    )

    companion object {
        const val DEFAULT_LIVE_SECONDS = 2
        const val MIN_LIVE_SECONDS = 1
        const val MAX_LIVE_SECONDS = 10
        const val DEFAULT_PICK_HOTKEY = "Ctrl+Alt+P"
        const val DEFAULT_RETENTION_COUNT = 200
        const val MIN_RETENTION_COUNT = 1
        const val DEFAULT_RETENTION_BYTES = 1L * 1024 * 1024 * 1024
        const val MIN_RETENTION_BYTES = 50L * 1024 * 1024
        const val MIN_PANE_WIDTH = 120
        const val MAX_PANE_WIDTH = 1600
        const val SCHEMA_VERSION = 1
    }
}
