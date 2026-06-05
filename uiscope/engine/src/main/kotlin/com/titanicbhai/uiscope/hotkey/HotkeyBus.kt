package com.titanicbhai.uiscope.hotkey

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class HotkeyEvent { PICK_MODE, REFRESH, SEARCH_FOCUS }

object HotkeyBus {
    private val _events = MutableSharedFlow<HotkeyEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()
    fun emit(event: HotkeyEvent) { _events.tryEmit(event) }
}
