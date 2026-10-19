package com.titanicbhai.uiscope.process

import com.titanicbhai.uiscope.error.Outcome
import java.net.ProxySelector
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class NetworkConfig(
    val connectTimeout: Duration = 10.seconds,
    /** Null = the JVM default selector (which follows the system proxy once [JdkNetworkGateway.enableSystemProxies] ran). */
    val proxySelector: ProxySelector? = null,
)

data class HttpTextResponse(val status: Int, val body: String)

data class DownloadResult(val bytesWritten: Long, val contentLength: Long?)

/**
 * The only HTTP entry point of the app (platform-tools download, update check). Used by nothing else, so proxy,
 * TLS and timeout behaviour is decided in one place.
 *
 * Failures are coded ([com.titanicbhai.uiscope.error.ErrorCodes] `NETWORK_*`, `DISK_FULL`). Cancelling the calling
 * coroutine aborts the request and deletes a partially written download.
 */
interface NetworkGateway {
    /** GET [url] and decode the body as UTF-8. 2xx = success; any other status is a `NETWORK_HTTP_STATUS` failure. */
    suspend fun getText(url: String, timeout: Duration): Outcome<HttpTextResponse>

    /**
     * GET [url] into [target] (overwritten). [onProgress] receives (bytes so far, total or null) on an I/O thread.
     * On any failure or cancellation the partial [target] is deleted. [timeout] bounds the whole transfer.
     */
    suspend fun download(
        url: String,
        target: Path,
        timeout: Duration,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Outcome<DownloadResult>
}
