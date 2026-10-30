package com.titanicbhai.uiscope.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one way to read and change settings. [settings] always holds the newest value: an [update] is visible to the very next read
 * (the old code let "Back" read settings before the asynchronous save finished: B12). Persistence happens after the change.
 */
interface SettingsStore {
    val settings: StateFlow<Settings>

    fun update(transform: (Settings) -> Settings)

    fun reset()
}

/** A store that keeps everything in memory: used by tests and as the base of the file-backed store. */
open class InMemorySettingsStore(initial: Settings = Settings()) : SettingsStore {
    private val state = MutableStateFlow(initial.sanitized())
    override val settings: StateFlow<Settings> = state.asStateFlow()

    override fun update(transform: (Settings) -> Settings) {
        // a compare-and-set loop (not MutableStateFlow.update) so the code behaves the same on every coroutines version
        while (true) {
            val current = state.value
            if (state.compareAndSet(current, transform(current).sanitized())) break
        }
        changed()
    }

    override fun reset() {
        state.value = Settings()
        changed()
    }

    /** Called after every change; the file-backed store persists here. */
    protected open fun changed() = Unit
}
