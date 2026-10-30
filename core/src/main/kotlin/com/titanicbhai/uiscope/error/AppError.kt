package com.titanicbhai.uiscope.error

/** What the user can do about a failure. The UI maps this (and the code) to copy and buttons in Phase 4. */
enum class Remedy {
    RETRY,
    CHECK_NETWORK,
    FREE_DISK_SPACE,
    REPORT_ISSUE,
}

/**
 * A failure that is shown to the user, never thrown across engine boundaries (plan §3.3).
 * [code] is a stable identifier (see [ErrorCodes]); [detail] is a short, non-sensitive explanation.
 */
sealed interface AppError {
    val code: String
    val detail: String?
    val remedy: Remedy?
}

/** The normal case: a known failure with a stable code. */
data class CodedError(
    override val code: String,
    override val detail: String? = null,
    override val remedy: Remedy? = null,
) : AppError

/** A bug: something nobody planned for. Carries the cause so it reaches the log, never the screen. */
data class UnexpectedError(
    val cause: Throwable,
    override val detail: String? = cause.message,
) : AppError {
    override val code: String get() = ErrorCodes.UNEXPECTED
    override val remedy: Remedy get() = Remedy.REPORT_ISSUE
}

/**
 * Stable error codes shared by engines, state holders and the UI copy table.
 * Domain codes (ADB, helper, snapshots…) are added by the tasks that own them (Appendix A).
 */
object ErrorCodes {
    const val UNEXPECTED = "UNEXPECTED"
    const val DISK_FULL = "DISK_FULL"
    const val DATA_DIR_UNAVAILABLE = "DATA_DIR_UNAVAILABLE"

    const val PROCESS_START_FAILED = "PROCESS_START_FAILED"
    const val PROCESS_TIMEOUT = "PROCESS_TIMEOUT"
    const val PROCESS_WRITE_FAILED = "PROCESS_WRITE_FAILED"

    const val NETWORK_BAD_URL = "NETWORK_BAD_URL"
    const val NETWORK_TIMEOUT = "NETWORK_TIMEOUT"
    const val NETWORK_DNS = "NETWORK_DNS"
    const val NETWORK_TLS = "NETWORK_TLS"
    const val NETWORK_CONNECT = "NETWORK_CONNECT"
    const val NETWORK_PROXY = "NETWORK_PROXY"
    const val NETWORK_HTTP_STATUS = "NETWORK_HTTP_STATUS"
    const val NETWORK_TOO_LARGE = "NETWORK_TOO_LARGE"
    const val NETWORK_IO = "NETWORK_IO"

    // Android (Appendix A)
    const val ADB_NOT_FOUND = "ADB_NOT_FOUND"
    const val ADB_VERSION_MISMATCH = "ADB_VERSION_MISMATCH"
    const val ADB_START_FAILED = "ADB_START_FAILED"
    const val NO_DEVICE = "NO_DEVICE"
    const val DEVICE_UNAUTHORIZED = "DEVICE_UNAUTHORIZED"
    const val DEVICE_OFFLINE = "DEVICE_OFFLINE"
    const val NO_PERMISSIONS = "NO_PERMISSIONS"
    const val DUMP_NULL_ROOT = "DUMP_NULL_ROOT"
    const val DUMP_IDLE_TIMEOUT = "DUMP_IDLE_TIMEOUT"
    const val UIAUTOMATOR_BUSY = "UIAUTOMATOR_BUSY"
    const val DUMP_TIMEOUT = "DUMP_TIMEOUT"
    const val SCREENSHOT_FAILED = "SCREENSHOT_FAILED"
    const val SECURE_WINDOW = "SECURE_WINDOW"
    const val PARSE_FAILED = "PARSE_FAILED"
    const val WIRELESS_PAIR_FAILED = "WIRELESS_PAIR_FAILED"
    const val WIRELESS_CONNECT_FAILED = "WIRELESS_CONNECT_FAILED"

    // ADB download (not in Appendix A; P4-10's "every code has copy" test must include them)
    const val ADB_DOWNLOAD_UNAVAILABLE = "ADB_DOWNLOAD_UNAVAILABLE"
    const val ADB_DOWNLOAD_CHECKSUM = "ADB_DOWNLOAD_CHECKSUM"
    const val ADB_DOWNLOAD_INVALID_ARCHIVE = "ADB_DOWNLOAD_INVALID_ARCHIVE"

    // Snapshots and export (Appendix A "General"; the first two are Round 5 additions)
    const val SNAPSHOT_SAVE_FAILED = "SNAPSHOT_SAVE_FAILED"
    const val SNAPSHOT_IMPORT_INVALID = "SNAPSHOT_IMPORT_INVALID"
    const val SNAPSHOT_DELETE_FAILED = "SNAPSHOT_DELETE_FAILED"
    const val SNAPSHOT_NOT_FOUND = "SNAPSHOT_NOT_FOUND"
    const val SNAPSHOT_STORE_UNAVAILABLE = "SNAPSHOT_STORE_UNAVAILABLE"
    const val EXPORT_FAILED = "EXPORT_FAILED"

    /** A capture document written by a newer UIScope than this one (not in Appendix A: the UI shows the detail text). */
    const val CAPTURE_SCHEMA_UNSUPPORTED = "CAPTURE_SCHEMA_UNSUPPORTED"
}

/** Result of an engine call: a value or an [AppError]. Engines return this instead of throwing. */
sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>

    data class Failure(val error: AppError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> =
    when (this) {
        is Outcome.Success -> Outcome.Success(transform(value))
        is Outcome.Failure -> this
    }

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> =
    when (this) {
        is Outcome.Success -> transform(value)
        is Outcome.Failure -> this
    }

inline fun <T, R> Outcome<T>.fold(onSuccess: (T) -> R, onFailure: (AppError) -> R): R =
    when (this) {
        is Outcome.Success -> onSuccess(value)
        is Outcome.Failure -> onFailure(error)
    }

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) action(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(action: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(error)
    return this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

fun Outcome<*>.errorOrNull(): AppError? = (this as? Outcome.Failure)?.error
