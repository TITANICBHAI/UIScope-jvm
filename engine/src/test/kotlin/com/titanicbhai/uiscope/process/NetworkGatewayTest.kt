package com.titanicbhai.uiscope.process

import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Remedy
import com.titanicbhai.uiscope.testing.FakeHttpServer
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.awaitTrue
import com.titanicbhai.uiscope.testing.blocking
import com.titanicbhai.uiscope.testing.withTempDir
import java.io.IOException
import java.net.ConnectException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** NetworkGateway against a fake local server: timeout, redirect, proxy, cancellation, error classification. */
class NetworkGatewayTest {
    private val dispatchers = StandardDispatcherProvider(Dispatchers.Unconfined)
    private fun gateway(config: NetworkConfig = NetworkConfig()) = JdkNetworkGateway(config, dispatchers)

    @Test
    fun `getText returns the body of a 200 response`() = blocking {
        FakeHttpServer().use { server ->
            server.text("/ok", "héllo")
            val response = gateway().getText(server.url("/ok"), 10.seconds).assertSuccess()
            assertEquals(200, response.status)
            assertEquals("héllo", response.body)
        }
    }

    @Test
    fun `redirects are followed`() = blocking {
        FakeHttpServer().use { server ->
            server.text("/final", "arrived")
            server.redirect("/start", "/final")
            assertEquals("arrived", gateway().getText(server.url("/start"), 10.seconds).assertSuccess().body)
        }
    }

    @Test
    fun `a non-2xx status is a coded failure`() = blocking {
        FakeHttpServer().use { server ->
            server.text("/missing", "nope", status = 404)
            val error = gateway().getText(server.url("/missing"), 10.seconds).assertFailure(ErrorCodes.NETWORK_HTTP_STATUS)
            assertTrue(error.detail!!.contains("404"), error.toString())
        }
    }

    @Test
    fun `a slow server hits the timeout`() = blocking {
        FakeHttpServer().use { server ->
            server.delayed("/slow", 5_000, "late")
            val started = System.nanoTime()
            val error = gateway().getText(server.url("/slow"), 1.seconds).assertFailure(ErrorCodes.NETWORK_TIMEOUT)
            assertEquals(Remedy.RETRY, error.remedy)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 4_000, "must not wait for the server")
        }
    }

    @Test
    fun `only http and https urls are accepted`() = blocking {
        gateway().getText("file:///etc/passwd", 5.seconds).assertFailure(ErrorCodes.NETWORK_BAD_URL)
        gateway().getText("not a url", 5.seconds).assertFailure(ErrorCodes.NETWORK_BAD_URL)
    }

    @Test
    fun `a closed port is a connect failure`() = blocking {
        val port = java.net.ServerSocket(0).use { it.localPort }
        gateway().getText("http://127.0.0.1:$port/", 5.seconds).assertFailure(ErrorCodes.NETWORK_CONNECT)
    }

    @Test
    fun `requests go through the configured proxy`() = blocking {
        FakeHttpServer().use { proxy ->
            proxy.text("/hello", "proxied")
            val selector = object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> = listOf(Proxy(Proxy.Type.HTTP, proxy.address))

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
            }
            val body = gateway(NetworkConfig(proxySelector = selector))
                .getText("http://example.invalid/hello", 10.seconds).assertSuccess().body
            assertEquals("proxied", body)
            assertEquals("example.invalid", proxy.requests.single().host?.substringBefore(':'))
        }
    }

    @Test
    fun `download writes the file and reports progress`() = blocking {
        FakeHttpServer().use { server ->
            server.bytes("/file", 2_000_000)
            withTempDir { dir ->
                val target = dir.resolve("file.bin")
                val lastProgress = AtomicLong(-1)
                val result = gateway().download(server.url("/file"), target, 30.seconds) { read, total ->
                    lastProgress.set(read)
                    assertEquals(2_000_000L, total)
                }.assertSuccess()
                assertEquals(2_000_000L, result.bytesWritten)
                assertEquals(2_000_000L, Files.size(target))
                assertEquals(2_000_000L, lastProgress.get())
            }
        }
    }

    @Test
    fun `a stalled download times out and leaves no partial file`() = blocking {
        FakeHttpServer().use { server ->
            server.stall("/stall", firstBytes = 1_000, stallMillis = 20_000)
            withTempDir { dir ->
                val target = dir.resolve("partial.bin")
                gateway().download(server.url("/stall"), target, 1.seconds).assertFailure(ErrorCodes.NETWORK_TIMEOUT)
                assertTrue(Files.notExists(target), "partial download must be deleted")
            }
        }
    }

    @Test
    fun `cancelling a download deletes the partial file`() = blocking {
        FakeHttpServer().use { server ->
            server.stall("/stall", firstBytes = 1_000, stallMillis = 20_000)
            withTempDir { dir ->
                val target = dir.resolve("partial.bin")
                val progressed = AtomicLong(0)
                val job = launch(Dispatchers.Default) {
                    gateway().download(server.url("/stall"), target, 60.seconds) { read, _ -> progressed.set(read) }
                }
                awaitTrue(message = "first bytes received") { progressed.get() > 0 }
                job.cancel()
                withTimeout(15_000) { job.join() }
                assertTrue(Files.notExists(target), "partial download must be deleted")
            }
        }
    }

    @Test
    fun `errors are classified into stable codes`() {
        assertEquals(ErrorCodes.NETWORK_TIMEOUT, classifyNetworkError(HttpTimeoutException("t")).code)
        assertEquals(ErrorCodes.NETWORK_TIMEOUT, classifyNetworkError(HttpConnectTimeoutException("t")).code)
        assertEquals(ErrorCodes.NETWORK_DNS, classifyNetworkError(IOException("wrapped", UnknownHostException("h"))).code)
        assertEquals(ErrorCodes.NETWORK_TLS, classifyNetworkError(SSLHandshakeException("bad cert")).code)
        assertEquals(ErrorCodes.NETWORK_CONNECT, classifyNetworkError(ConnectException("Connection refused")).code)
        assertEquals(ErrorCodes.NETWORK_PROXY, classifyNetworkError(ConnectException("Proxy refused the connection")).code)
        assertEquals(ErrorCodes.DISK_FULL, classifyNetworkError(FileSystemException("f", null, "No space left on device")).code)
        assertEquals(ErrorCodes.DISK_FULL, classifyNetworkError(FileSystemException("f", null, "There is not enough space on the disk")).code)
        assertEquals(ErrorCodes.NETWORK_IO, classifyNetworkError(IOException("boom")).code)
        assertEquals(Remedy.FREE_DISK_SPACE, classifyNetworkError(FileSystemException("f", null, "No space left on device")).remedy)
    }
}
