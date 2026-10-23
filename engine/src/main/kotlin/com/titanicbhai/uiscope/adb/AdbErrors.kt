package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Remedy

/**
 * Maps adb's and uiautomator's own messages to Appendix A codes. Only messages that adb itself prints are matched, and the detail
 * carries adb's first line (never the rest of an output, which may be a screen dump).
 */
object AdbErrors {
    private const val MAX_DETAIL = 300

    fun classify(output: String): AppError? {
        val text = output.lowercase()
        val code = when {
            Regex("""adb server version \(\d+\) doesn't match this client""").containsMatchIn(text) -> ErrorCodes.ADB_VERSION_MISMATCH
            "device unauthorized" in text || "error: device is unauthorized" in text -> ErrorCodes.DEVICE_UNAUTHORIZED
            "device offline" in text -> ErrorCodes.DEVICE_OFFLINE
            "insufficient permissions" in text || "no permissions" in text -> ErrorCodes.NO_PERMISSIONS
            "no devices/emulators found" in text || "no devices found" in text || Regex("""device '[^']*' not found""").containsMatchIn(text) -> ErrorCodes.NO_DEVICE
            "cannot connect to daemon" in text || "failed to start daemon" in text || "smartsocket listener" in text -> ErrorCodes.ADB_START_FAILED
            else -> return null
        }
        return CodedError(code, firstLine(output), Remedy.RETRY)
    }

    /** adb's first non-empty line, shortened: what the UI shows as "adb said …". */
    fun firstLine(output: String): String =
        output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(MAX_DETAIL)
}
