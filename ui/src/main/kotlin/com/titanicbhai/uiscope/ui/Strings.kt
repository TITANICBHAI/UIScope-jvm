package com.titanicbhai.uiscope.ui

import com.titanicbhai.uiscope.error.ErrorCodes

/**
 * All user-visible copy in one place (plan §3.3, §4.12): core and engine carry error **codes only**, the words live here, so
 * localization stays possible and the UI never invents ad-hoc messages. English only in v1.
 *
 * The error table is Appendix A of the plan plus the codes the implementation added since (see docs/PROGRESS.md) — every code in
 * [ErrorCodes] has an entry, and P4-10's "every code has copy" test holds because of that. Each entry is
 * **title · what happened / likely cause · primary action**; the UI adds *[Copy details]* itself.
 */
object Strings {

    /** The copy for one error code. [action] is the label of the primary button, null when there is none. */
    data class ErrorCopy(val title: String, val what: String, val action: String? = null)

    /** Looks up [code]; an unknown code falls back to the UNEXPECTED copy (the code itself is shown in *Copy details*). */
    fun error(code: String): ErrorCopy = errors[code] ?: errors.getValue(ErrorCodes.UNEXPECTED)

    // Shared action labels.
    const val ACTION_RETRY = "Retry"
    const val ACTION_COPY_DETAILS = "Copy details"
    const val ACTION_DISMISS = "Dismiss"
    const val ACTION_OPEN_GUIDE = "Open guide"
    const val ACTION_RESTART_ADB = "Restart adb"
    const val ACTION_DOWNLOAD_ADB = "Download platform-tools"
    const val ACTION_RESCAN = "Rescan"

    private val errors: Map<String, ErrorCopy> = mapOf(
        // ── Android (Appendix A) ────────────────────────────────────────────────────────────────
        ErrorCodes.ADB_NOT_FOUND to ErrorCopy(
            "ADB not found",
            "UIScope needs Android's adb tool to talk to your device.",
            ACTION_DOWNLOAD_ADB,
        ),
        ErrorCodes.ADB_VERSION_MISMATCH to ErrorCopy(
            "Another adb is running",
            "Android Studio or another tool started a different adb version.",
            ACTION_RESTART_ADB,
        ),
        ErrorCodes.ADB_START_FAILED to ErrorCopy(
            "adb couldn't start",
            "The adb server did not start (the port may be busy).",
            ACTION_RETRY,
        ),
        ErrorCodes.NO_DEVICE to ErrorCopy(
            "No device found",
            "Check the data cable (not charge-only), USB debugging and file-transfer mode; on Windows install the phone maker's or Google USB driver.",
            ACTION_RESCAN,
        ),
        ErrorCodes.DEVICE_UNAUTHORIZED to ErrorCopy(
            "Allow USB debugging on the phone",
            "Unlock the phone and tap Allow on the prompt (tick Always allow).",
            ACTION_RESCAN,
        ),
        ErrorCodes.DEVICE_OFFLINE to ErrorCopy(
            "Device offline",
            "Unplug and replug the cable, or toggle USB debugging.",
            ACTION_RESTART_ADB,
        ),
        ErrorCodes.NO_PERMISSIONS to ErrorCopy(
            "Linux can't access the device",
            "Add a udev rule for your phone's vendor id and replug the cable.",
            ACTION_OPEN_GUIDE,
        ),
        ErrorCodes.DUMP_NULL_ROOT to ErrorCopy(
            "The screen can't be read right now",
            "The app may be transitioning or block accessibility.",
            ACTION_RETRY,
        ),
        ErrorCodes.DUMP_IDLE_TIMEOUT to ErrorCopy(
            "The screen keeps animating",
            "Pause the video, animation or spinner and try again.",
            ACTION_RETRY,
        ),
        ErrorCodes.UIAUTOMATOR_BUSY to ErrorCopy(
            "Another automation tool is using the device",
            "Stop Appium, uiautomator2 or Maestro sessions on this device.",
            ACTION_RETRY,
        ),
        ErrorCodes.DUMP_TIMEOUT to ErrorCopy(
            "Timed out reading the screen",
            "The device is slow or busy.",
            ACTION_RETRY,
        ),
        ErrorCodes.SCREENSHOT_FAILED to ErrorCopy(
            "Couldn't take a screenshot",
            "The element tree is still shown if it is available.",
            ACTION_RETRY,
        ),
        ErrorCodes.SECURE_WINDOW to ErrorCopy(
            "This screen may block capture",
            "Banking and DRM apps and locked screens hide their content; the tree may be incomplete.",
            ACTION_DISMISS,
        ),
        ErrorCodes.PARSE_FAILED to ErrorCopy(
            "Couldn't read the UI dump",
            "The device returned something UIScope could not parse.",
            ACTION_COPY_DETAILS,
        ),
        ErrorCodes.WIRELESS_PAIR_FAILED to ErrorCopy(
            "Pairing didn't work",
            "Both devices must be on the same Wi-Fi network and the pairing code is valid only once.",
            ACTION_RETRY,
        ),
        ErrorCodes.WIRELESS_CONNECT_FAILED to ErrorCopy(
            "Couldn't connect over Wi-Fi",
            "The connect port differs from the pairing port; check the address and try again.",
            ACTION_RETRY,
        ),
        ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE to ErrorCopy(
            "Couldn't download platform-tools",
            "The download service could not be reached. Check the connection, or install adb yourself and point UIScope at it.",
            ACTION_RETRY,
        ),
        ErrorCodes.ADB_DOWNLOAD_CHECKSUM to ErrorCopy(
            "The download is not genuine",
            "The downloaded archive's checksum does not match Google's published value; it was deleted.",
            ACTION_RETRY,
        ),
        ErrorCodes.ADB_DOWNLOAD_INVALID_ARCHIVE to ErrorCopy(
            "The download is damaged",
            "The archive could not be opened; it was deleted.",
            ACTION_RETRY,
        ),
        // ── Windows (Appendix A; the codes arrive with the Phase 3 engine) ─────────────────────
        "UIA_UNAVAILABLE" to ErrorCopy(
            "Windows inspection isn't available",
            "UIScope's helper could not start — it may be blocked by security software.",
            ACTION_RETRY,
        ),
        "ELEVATED_TARGET" to ErrorCopy(
            "That window runs as administrator",
            "Windows stops normal apps from reading elevated windows. Close UIScope, right-click it and choose Run as administrator.",
            ACTION_DISMISS,
        ),
        "WINDOW_GONE" to ErrorCopy(
            "The window was closed",
            "Pick a window again.",
            "Pick again",
        ),
        "WINDOW_MINIMIZED" to ErrorCopy(
            "The window is minimized",
            "Restore it for a screenshot — the element tree was still read.",
            ACTION_DISMISS,
        ),
        "LIMITED_ACCESSIBILITY" to ErrorCopy(
            "This app exposes little accessibility information",
            "Chromium/Electron: start it with --force-renderer-accessibility. Qt: set QT_ACCESSIBILITY=1. Java: enable the Java Access Bridge.",
            ACTION_OPEN_GUIDE,
        ),
        "TREE_TRUNCATED" to ErrorCopy(
            "Tree truncated at 10,000 elements",
            "The tree is too large and was cut at the limit.",
            ACTION_DISMISS,
        ),
        "HOTKEY_IN_USE" to ErrorCopy(
            "That shortcut is taken",
            "Another app uses this shortcut; choose a different one in Settings.",
            "Change shortcut",
        ),
        // ── General (Appendix A) ───────────────────────────────────────────────────────────────
        ErrorCodes.SNAPSHOT_SAVE_FAILED to ErrorCopy(
            "Couldn't save the snapshot",
            "Writing to the snapshots folder failed.",
            ACTION_RETRY,
        ),
        ErrorCodes.SNAPSHOT_IMPORT_INVALID to ErrorCopy(
            "That file can't be imported",
            "The file is not a .uiscope bundle UIScope made.",
            null,
        ),
        ErrorCodes.SNAPSHOT_DELETE_FAILED to ErrorCopy(
            "Couldn't delete the snapshot",
            "The files may be open in another program; UIScope retries at the next start.",
            ACTION_DISMISS,
        ),
        ErrorCodes.SNAPSHOT_NOT_FOUND to ErrorCopy(
            "The snapshot is gone",
            "It was deleted elsewhere or its files are missing.",
            ACTION_DISMISS,
        ),
        ErrorCodes.SNAPSHOT_STORE_UNAVAILABLE to ErrorCopy(
            "Snapshots are unavailable",
            "The snapshot database could not be opened this session.",
            ACTION_DISMISS,
        ),
        ErrorCodes.CAPTURE_SCHEMA_UNSUPPORTED to ErrorCopy(
            "This file was made by a newer UIScope",
            "Its capture format is newer than this version understands.",
            null,
        ),
        ErrorCodes.EXPORT_FAILED to ErrorCopy(
            "Couldn't export",
            "Writing the export failed.",
            ACTION_RETRY,
        ),
        ErrorCodes.DISK_FULL to ErrorCopy(
            "The disk is full",
            "Free up space on the drive that holds your user folder and try again.",
            "Free disk space",
        ),
        ErrorCodes.DATA_DIR_UNAVAILABLE to ErrorCopy(
            "Couldn't create the data folder",
            "UIScope could not create or use its data folder.",
            ACTION_COPY_DETAILS,
        ),
        // ── Process/network/storage infrastructure codes (P0-5, Appendix A style) ──────────────
        ErrorCodes.PROCESS_START_FAILED to ErrorCopy(
            "Couldn't start a program",
            "A program UIScope needs did not start.",
            ACTION_COPY_DETAILS,
        ),
        ErrorCodes.PROCESS_TIMEOUT to ErrorCopy(
            "A program took too long",
            "The program did not finish in time and was stopped.",
            ACTION_RETRY,
        ),
        ErrorCodes.PROCESS_WRITE_FAILED to ErrorCopy(
            "Couldn't talk to a program",
            "Writing to the program's input failed; it may have exited.",
            ACTION_RETRY,
        ),
        ErrorCodes.NETWORK_BAD_URL to ErrorCopy(
            "Invalid address",
            "The address of a network resource is not valid.",
            ACTION_COPY_DETAILS,
        ),
        ErrorCodes.NETWORK_TIMEOUT to ErrorCopy(
            "The network is too slow",
            "The request timed out.",
            ACTION_RETRY,
        ),
        ErrorCodes.NETWORK_DNS to ErrorCopy(
            "Address not found",
            "The server's name could not be resolved — check the connection.",
            ACTION_RETRY,
        ),
        ErrorCodes.NETWORK_TLS to ErrorCopy(
            "Secure connection failed",
            "The secure (HTTPS) handshake failed.",
            ACTION_COPY_DETAILS,
        ),
        ErrorCodes.NETWORK_CONNECT to ErrorCopy(
            "Couldn't reach the server",
            "The connection was refused or timed out — check the network and any proxy.",
            ACTION_RETRY,
        ),
        ErrorCodes.NETWORK_PROXY to ErrorCopy(
            "Proxy problem",
            "The configured proxy could not be reached or rejected the request.",
            ACTION_COPY_DETAILS,
        ),
        ErrorCodes.NETWORK_HTTP_STATUS to ErrorCopy(
            "The server reported an error",
            "The server answered with an HTTP error.",
            ACTION_RETRY,
        ),
        ErrorCodes.NETWORK_TOO_LARGE to ErrorCopy(
            "The answer is too large",
            "The server sent more data than UIScope accepts.",
            ACTION_COPY_DETAILS,
        ),
        ErrorCodes.NETWORK_IO to ErrorCopy(
            "Network error",
            "The connection failed while transferring data.",
            ACTION_RETRY,
        ),
        ErrorCodes.UNEXPECTED to ErrorCopy(
            "Something went wrong",
            "An unexpected error occurred. Copy the details if you report it.",
            ACTION_COPY_DETAILS,
        ),
    )
}
