package com.titanicbhai.uiscope.process

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.error.Remedy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.ProxySelector
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

class JdkNetworkGateway(
    config: NetworkConfig = NetworkConfig(),
    private val dispatchers: DispatcherProvider,
    private val logger: Logger = Logger.getLogger("uiscope.network"),
) : NetworkGateway {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(config.connectTimeout.toJavaDuration())
        .also { builder -> (config.proxySelector ?: ProxySelector.getDefault())?.let { builder.proxy(it) } }
        .build()

    override suspend fun getText(url: String, timeout: Duration): Outcome<HttpTextResponse> =
        guarded(url, timeout) { request ->
            val response = client.sendSuspending(request)
            response.body().use { body ->
                val status = response.statusCode()
                if (status !in HTTP_OK) return@guarded httpStatusFailure(status)
                val bytes = readLimited(body, MAX_TEXT_BYTES)
                    ?: return@guarded Outcome.Failure(
                        CodedError(ErrorCodes.NETWORK_TOO_LARGE, "The response was larger than $MAX_TEXT_BYTES bytes"),
                    )
                Outcome.Success(HttpTextResponse(status, bytes.toString(Charsets.UTF_8)))
            }
        }

    override suspend fun download(
        url: String,
        target: Path,
        timeout: Duration,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): Outcome<DownloadResult> =
        guarded(url, timeout) { request ->
            var completed = false
            try {
                val response = client.sendSuspending(request)
                val outcome = response.body().use { body ->
                    val status = response.statusCode()
                    if (status !in HTTP_OK) return@use httpStatusFailure(status)
                    val total = response.headers().firstValueAsLong("Content-Length").let { if (it.isPresent) it.asLong else null }
                    val written = copyToFile(body, target, total, onProgress)
                    Outcome.Success(DownloadResult(written, total))
                }
                completed = outcome is Outcome.Success
                outcome
            } finally {
                // Failure, timeout and cancellation all leave no partial file behind.
                if (!completed) deleteQuietly(target)
            }
        }

    /** Common envelope: URL check, overall timeout, and mapping of I/O failures to coded errors. */
    private suspend fun <T> guarded(
        url: String,
        timeout: Duration,
        block: suspend (HttpRequest) -> Outcome<T>,
    ): Outcome<T> {
        val request = buildRequest(url, timeout) ?: return Outcome.Failure(
            CodedError(ErrorCodes.NETWORK_BAD_URL, "Only http and https addresses are supported"),
        )
        return try {
            withTimeout(timeout.inWholeMilliseconds.coerceAtLeast(1)) { block(request) }
        } catch (e: TimeoutCancellationException) {
            logger.log(Level.FINE, "request timed out", e)
            Outcome.Failure(CodedError(ErrorCodes.NETWORK_TIMEOUT, "The server did not answer in time", Remedy.RETRY))
        } catch (e: IOException) {
            logger.log(Level.INFO, "network request failed: ${e.javaClass.simpleName}")
            Outcome.Failure(classifyNetworkError(e))
        }
    }

    private fun buildRequest(url: String, timeout: Duration): HttpRequest? {
        val uri = try {
            URI(url)
        } catch (e: java.net.URISyntaxException) {
            logger.log(Level.FINE, "bad url", e)
            return null
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null
        return HttpRequest.newBuilder(uri)
            .timeout(timeout.toJavaDuration())
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
    }

    private fun httpStatusFailure(status: Int): Outcome.Failure =
        Outcome.Failure(CodedError(ErrorCodes.NETWORK_HTTP_STATUS, "The server answered HTTP $status", Remedy.RETRY))

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun readLimited(body: InputStream, limit: Int): ByteArray? =
        runInterruptible(dispatchers.io) {
            asInterruptible {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = body.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > limit) return@asInterruptible null
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun copyToFile(
        body: InputStream,
        target: Path,
        total: Long?,
        onProgress: (Long, Long?) -> Unit,
    ): Long =
        runInterruptible(dispatchers.io) {
            asInterruptible {
                var written = 0L
                Files.newOutputStream(target).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = body.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        onProgress(written, total)
                    }
                }
                written
            }
        }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not delete partial download", e)
        }
    }

    companion object {
        private const val USER_AGENT = "UIScope"
        private const val MAX_TEXT_BYTES = 10 * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024
        private val HTTP_OK = 200..299

        /** Call once at startup, before any network use, so the JVM follows the operating system's proxy settings. */
        fun enableSystemProxies() {
            System.setProperty("java.net.useSystemProxies", "true")
        }
    }
}

private suspend fun HttpClient.sendSuspending(request: HttpRequest): HttpResponse<InputStream> =
    suspendCancellableCoroutine { continuation ->
        val future = sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        continuation.invokeOnCancellation { future.cancel(true) }
        future.whenComplete { response, error ->
            if (error != null) continuation.resumeWithException(unwrap(error)) else continuation.resume(response)
        }
    }

/**
 * The JDK reports a thread interrupt during a blocked read as an `IOException` (with the interrupt flag set). Turn that
 * back into an [InterruptedException] so `runInterruptible` reports it as coroutine cancellation (a timeout or a cancel)
 * instead of a network failure.
 */
private inline fun <T> asInterruptible(block: () -> T): T =
    try {
        block()
    } catch (e: IOException) {
        if (Thread.interrupted()) throw InterruptedException("interrupted while waiting for the network").also { it.initCause(e) }
        throw e
    }

private fun unwrap(error: Throwable): Throwable =
    if ((error is CompletionException || error is ExecutionException) && error.cause != null) unwrap(error.cause!!) else error

/** Maps any failure from the HTTP stack or the file system to a stable [AppError] code. */
internal fun classifyNetworkError(error: Throwable): AppError {
    val chain = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    return when {
        chain.any { it is HttpTimeoutException } ->
            CodedError(ErrorCodes.NETWORK_TIMEOUT, "The server did not answer in time", Remedy.RETRY)
        chain.any { it is UnknownHostException } ->
            CodedError(ErrorCodes.NETWORK_DNS, "The server name could not be resolved", Remedy.CHECK_NETWORK)
        chain.any { it is SSLException } ->
            CodedError(ErrorCodes.NETWORK_TLS, "A secure connection could not be established", Remedy.CHECK_NETWORK)
        chain.any { isDiskFull(it) } ->
            CodedError(ErrorCodes.DISK_FULL, "There is not enough free disk space", Remedy.FREE_DISK_SPACE)
        chain.any { it is ConnectException && it.message.orEmpty().contains("proxy", ignoreCase = true) } ->
            CodedError(ErrorCodes.NETWORK_PROXY, "The proxy server refused the connection", Remedy.CHECK_NETWORK)
        chain.any { it is ConnectException } ->
            CodedError(ErrorCodes.NETWORK_CONNECT, "Could not connect to the server", Remedy.CHECK_NETWORK)
        else -> CodedError(ErrorCodes.NETWORK_IO, "The download was interrupted", Remedy.RETRY)
    }
}

private fun isDiskFull(error: Throwable): Boolean {
    if (error !is FileSystemException && error !is IOException) return false
    val text = (error.message.orEmpty() + " " + ((error as? FileSystemException)?.reason.orEmpty())).lowercase()
    return text.contains("no space left") || text.contains("not enough space") || text.contains("disk full")
}

private const val MAX_CAUSE_DEPTH = 8
