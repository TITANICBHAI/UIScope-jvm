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
