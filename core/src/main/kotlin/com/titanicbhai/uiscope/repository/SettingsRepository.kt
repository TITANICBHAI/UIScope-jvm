package com.titanicbhai.uiscope.repository

import com.titanicbhai.uiscope.db.DatabaseFactory

class SettingsRepository {
    private val queries get() = DatabaseFactory.getDatabase().appSettingsQueries

    fun set(key: String, value: String) = queries.upsertSetting(key, value)

    fun get(key: String, default: String? = null): String? =
        queries.selectSetting(key).executeAsOneOrNull() ?: default

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        get(key)?.toBooleanStrictOrNull() ?: default

    fun setBoolean(key: String, value: Boolean) = set(key, value.toString())

    fun getLong(key: String, default: Long = 0L): Long =
        get(key)?.toLongOrNull() ?: default

    fun setLong(key: String, value: Long) = set(key, value.toString())

    companion object Keys {
        const val LAST_MODE = "last_mode"
        const val THEME = "theme"
        const val ADB_PATH = "adb_path"
        const val ONBOARDING_SEEN = "onboarding_seen"
        const val HISTORY_LIMIT = "history_limit"
        const val POLLING_INTERVAL_MS = "polling_interval_ms"
        const val AUTO_REFRESH = "auto_refresh"
        const val HIGHLIGHT_COLOR_PC = "highlight_color_pc"
        const val HIGHLIGHT_COLOR_ANDROID = "highlight_color_android"
        const val DEFAULT_EXPORT_FORMAT = "default_export_format"
    }
}
