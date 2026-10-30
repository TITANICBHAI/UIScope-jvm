package com.titanicbhai.uiscope.update

import com.titanicbhai.uiscope.AppInfo
import com.titanicbhai.uiscope.SemVer
import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.json.JsonReader
import com.titanicbhai.uiscope.process.NetworkGateway
import java.net.URI
import java.net.URISyntaxException
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.seconds

sealed interface UpdateCheck {
    /** Nothing was requested: Store build or the user turned the check off. No network call was made. */
    data object Disabled : UpdateCheck

    data object UpToDate : UpdateCheck

    /** [whatsNewUrl] is the release page; UIScope never downloads the update itself. */
    data class Available(val version: String, val whatsNewUrl: String) : UpdateCheck

    /** Logged by the checker; the UI shows nothing (plan P7-5). */
    data class Failed(val error: AppError) : UpdateCheck
}

/**
 * Update check v2 (plan P7-5, M17): opt-out, semantic-version comparison (`latest > current` only), URLs from [AppInfo], real JSON
 * parsing, disabled in the Store build, uses the [NetworkGateway], downloads nothing, failures are logged and not shown.
 */
class UpdateChecker(
    private val gateway: NetworkGateway,
    private val appInfo: AppInfo,
    private val enabled: () -> Boolean,
    private val logger: Logger = Logger.getLogger("uiscope.update"),
) {
    suspend fun check(): UpdateCheck {
        if (appInfo.isStoreBuild || !enabled()) return UpdateCheck.Disabled
        val current = SemVer.parse(appInfo.version) ?: return failed(ErrorCodes.PARSE_FAILED, "The running version is not a semantic version")
        val response = when (val r = gateway.getText(appInfo.releasesApiUrl, TIMEOUT)) {
            is Outcome.Failure -> return failed(r.error.code, "The release information could not be fetched", r.error)
            is Outcome.Success -> r.value
        }
        if (response.body.length > MAX_BODY_CHARS) return failed(ErrorCodes.PARSE_FAILED, "The release information is unexpectedly large")
        val root = (JsonReader.parse(response.body) as? Outcome.Success)?.value as? Map<*, *>
            ?: return failed(ErrorCodes.PARSE_FAILED, "The release information is not a JSON object")
        if (root["draft"] == true || root["prerelease"] == true) return UpdateCheck.UpToDate
        val tag = root["tag_name"] as? String ?: return failed(ErrorCodes.PARSE_FAILED, "The release has no tag name")
        val latest = SemVer.parse(tag) ?: return failed(ErrorCodes.PARSE_FAILED, "The release tag is not a semantic version")
        if (latest.isPreRelease && !current.isPreRelease) return UpdateCheck.UpToDate
        if (latest <= current) return UpdateCheck.UpToDate
        return UpdateCheck.Available(latest.toString(), whatsNewUrl(root["html_url"] as? String))
    }

    /** The release page when it is an https link on the repository's own host, otherwise the repository's releases page. */
    private fun whatsNewUrl(candidate: String?): String {
        val fallback = appInfo.repoUrl.trimEnd('/') + "/releases"
        if (candidate == null) return fallback
        return try {
            val uri = URI(candidate)
            val repoHost = URI(appInfo.repoUrl).host
            if (uri.scheme == "https" && uri.host != null && uri.host.equals(repoHost, ignoreCase = true)) candidate else fallback
        } catch (e: URISyntaxException) {
            logger.log(Level.FINE, "release link is not a valid URI; using the releases page", e)
            fallback
        }
    }

    private fun failed(code: String, why: String, cause: AppError? = null): UpdateCheck.Failed {
        logger.log(Level.INFO, "update check failed: $why (${cause?.code ?: code})")
        return UpdateCheck.Failed(CodedError(code, why))
    }

    private companion object {
        val TIMEOUT = 10.seconds
        const val MAX_BODY_CHARS = 2_000_000
    }
}
