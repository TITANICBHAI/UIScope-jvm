package com.titanicbhai.uiscope.storage

import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.json.JsonReader
import com.titanicbhai.uiscope.json.JsonWriter
import com.titanicbhai.uiscope.settings.ExportFormat
import com.titanicbhai.uiscope.settings.InspectTarget
import com.titanicbhai.uiscope.settings.LogLevelSetting
import com.titanicbhai.uiscope.settings.Renderer
import com.titanicbhai.uiscope.settings.Settings
import com.titanicbhai.uiscope.settings.ThemeMode

/**
 * The text of `settings.json`. Keys are the property names of [Settings]. Reading is forgiving on purpose: unknown keys (including ones
 * from a newer version) are ignored, a value of the wrong type or an unknown enum name falls back to that setting's default, and the
 * result is passed through [Settings.sanitized]. Only text that is not a JSON object at all is an error.
 */
object SettingsJson {
    fun encode(s: Settings): String {
        val fields = listOf(
            "schemaVersion" to Settings.SCHEMA_VERSION.toString(),
            "theme" to q(s.theme.name),
            "checkForUpdates" to s.checkForUpdates.toString(),
            "defaultExportFormat" to q(s.defaultExportFormat.name),
            "lastFolder" to qn(s.lastFolder),
            "lastTarget" to q(s.lastTarget.name),
            "adbPath" to qn(s.adbPath),
            "lastSerial" to qn(s.lastSerial),
            "lastWirelessHost" to qn(s.lastWirelessHost),
            "liveIntervalSeconds" to s.liveIntervalSeconds.toString(),
            "pickHotkeyEnabled" to s.pickHotkeyEnabled.toString(),
            "pickHotkey" to q(s.pickHotkey),
            "hideWhilePicking" to s.hideWhilePicking.toString(),
            "autoSaveSnapshots" to s.autoSaveSnapshots.toString(),
            "retentionCount" to s.retentionCount.toString(),
            "retentionBytes" to s.retentionBytes.toString(),
            "renderer" to q(s.renderer.name),
            "logLevel" to q(s.logLevel.name),
            "windowWidth" to s.windowWidth.toString(),
            "windowHeight" to s.windowHeight.toString(),
            "windowX" to s.windowX.toString(),
            "windowY" to s.windowY.toString(),
            "windowMaximized" to s.windowMaximized.toString(),
            "treePaneWidth" to s.treePaneWidth.toString(),
            "detailsPaneWidth" to s.detailsPaneWidth.toString(),
        )
        return "{\n" + fields.joinToString(",\n") { (k, v) -> "  ${q(k)}: $v" } + "\n}\n"
    }

    fun decode(text: String): Outcome<Settings> {
        val root = when (val parsed = JsonReader.parse(text)) {
            is Outcome.Failure -> return parsed
            is Outcome.Success -> parsed.value
        }
        @Suppress("UNCHECKED_CAST")
        val map = root as? Map<String, Any?> ?: return Outcome.Failure(CodedError(ErrorCodes.PARSE_FAILED, "settings.json is not a JSON object"))
        val d = Settings()
        fun bool(key: String, default: Boolean) = map[key] as? Boolean ?: default
        fun int(key: String, default: Int) = (map[key] as? Long)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: default
        fun intOrNull(key: String): Int? = if (map.containsKey(key)) int(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null
        fun long(key: String, default: Long) = map[key] as? Long ?: default
        fun str(key: String, default: String) = map[key] as? String ?: default
        fun strOrNull(key: String) = map[key] as? String
        fun <T : Enum<T>> enum(key: String, values: Array<T>, default: T) = (map[key] as? String)?.let { name -> values.firstOrNull { it.name == name } } ?: default
        return Outcome.Success(
            Settings(
                theme = enum("theme", ThemeMode.entries.toTypedArray(), d.theme),
                checkForUpdates = bool("checkForUpdates", d.checkForUpdates),
                defaultExportFormat = enum("defaultExportFormat", ExportFormat.entries.toTypedArray(), d.defaultExportFormat),
                lastFolder = strOrNull("lastFolder"),
                lastTarget = enum("lastTarget", InspectTarget.entries.toTypedArray(), d.lastTarget),
                adbPath = strOrNull("adbPath"),
                lastSerial = strOrNull("lastSerial"),
                lastWirelessHost = strOrNull("lastWirelessHost"),
                liveIntervalSeconds = int("liveIntervalSeconds", d.liveIntervalSeconds),
                pickHotkeyEnabled = bool("pickHotkeyEnabled", d.pickHotkeyEnabled),
                pickHotkey = str("pickHotkey", d.pickHotkey),
                hideWhilePicking = bool("hideWhilePicking", d.hideWhilePicking),
                autoSaveSnapshots = bool("autoSaveSnapshots", d.autoSaveSnapshots),
                retentionCount = int("retentionCount", d.retentionCount),
                retentionBytes = long("retentionBytes", d.retentionBytes),
                renderer = enum("renderer", Renderer.entries.toTypedArray(), d.renderer),
                logLevel = enum("logLevel", LogLevelSetting.entries.toTypedArray(), d.logLevel),
                windowWidth = intOrNull("windowWidth"),
                windowHeight = intOrNull("windowHeight"),
                windowX = intOrNull("windowX"),
                windowY = intOrNull("windowY"),
                windowMaximized = bool("windowMaximized", d.windowMaximized),
                treePaneWidth = intOrNull("treePaneWidth"),
                detailsPaneWidth = intOrNull("detailsPaneWidth"),
            ).sanitized(),
        )
    }

    private fun q(value: String) = JsonWriter.string(value)

    private fun qn(value: String?) = if (value == null) "null" else q(value)
}
