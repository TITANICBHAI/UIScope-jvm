package com.titanicbhai.uiscope.hotkey

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.NativeInputEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import java.util.logging.Level
import java.util.logging.Logger

class GlobalHotkeyManager {

    private var registered = false
    private var listener: NativeKeyListener? = null

    fun register(
        onPickMode: () -> Unit,
        onRefresh: () -> Unit,
        onSearchFocus: () -> Unit
    ) {
        suppressJNativeHookLogs()
        try {
            GlobalScreen.registerNativeHook()
            registered = true
            listener = object : NativeKeyListener {
                override fun nativeKeyPressed(e: NativeKeyEvent) {
                    val alt   = (e.modifiers and NativeInputEvent.ALT_MASK)   != 0
                    val shift = (e.modifiers and NativeInputEvent.SHIFT_MASK) != 0
                    val ctrl  = (e.modifiers and NativeInputEvent.CTRL_MASK)  != 0
                    when {
                        // Alt+Shift+P  → pick mode
                        alt && shift && e.keyCode == NativeKeyEvent.VC_P -> onPickMode()
                        // R (no modifiers) → refresh tree
                        e.keyCode == NativeKeyEvent.VC_R && !alt && !ctrl && !shift -> onRefresh()
                        // Ctrl+F → search focus
                        ctrl && !alt && !shift && e.keyCode == NativeKeyEvent.VC_F -> onSearchFocus()
                    }
                }
                override fun nativeKeyReleased(e: NativeKeyEvent) {}
                override fun nativeKeyTyped(e: NativeKeyEvent) {}
            }.also { GlobalScreen.addNativeKeyListener(it) }
        } catch (_: NativeHookException) {
            // Hotkeys optional — silently skip if the hook can't register
        } catch (_: Throwable) {
            // Catches UnsatisfiedLinkError (missing libxkbcommon-x11 / native deps) and any
            // other error so the app always starts regardless of hotkey availability.
        }
    }

    fun unregister() {
        try {
            listener?.let { GlobalScreen.removeNativeKeyListener(it) }
            if (registered) GlobalScreen.unregisterNativeHook()
        } catch (_: Exception) {}
        registered = false
        listener = null
    }

    private fun suppressJNativeHookLogs() {
        val pkgLogger = Logger.getLogger(GlobalScreen::class.java.`package`?.name ?: "")
        pkgLogger.level = Level.OFF
        pkgLogger.useParentHandlers = false
    }
}
