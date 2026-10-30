package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.settings.SettingsStore

/** Keeps the chosen adb path in the settings file ([com.titanicbhai.uiscope.settings.Settings.adbPath]) instead of a separate file. */
class SettingsAdbPathStore(private val settings: SettingsStore) : AdbPathStore {
    override fun load(): String? = settings.settings.value.adbPath

    override fun save(path: String) = settings.update { it.copy(adbPath = path) }
}
