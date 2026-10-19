package com.titanicbhai.uiscope.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/** A local HTTP server on a random loopback port for NetworkGateway tests. Nothing here touches the internet. */
class FakeHttpServer : AutoCloseable {
    data class RecordedRequest(val method: String, val uri: String, val host: String?)

    private val logger = Logger.getLogger("uiscope.test.fakehttp")
    private val routes = ConcurrentHashMap<String, (HttpExchange) -> Unit>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val port: Int get() = server.address.port
    val address: InetSocketAddress get() = InetSocketAddress(InetAddress.getLoopbackAddress(), port)

    init {
        server.executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "fake-http").apply { isDaemon = true } }
        server.createContext("/") { exchange ->
            try {
                requests += RecordedRequest(exchange.requestMethod, exchange.requestURI.toString(), exchange.requestHeaders.getFirst("Host"))
                val handler = routes[exchange.requestURI.path]
                if (handler == null) respond(exchange, 404, "not found") else handler(exchange)
            } catch (e: IOException) {
                logger.log(Level.FINE, "client went away", e)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    fun url(path: String): String = "http://127.0.0.1:$port$path"

    fun route(path: String, handler: (HttpExchange) -> Unit) {
        routes[path] = handler
    }

    fun text(path: String, body: String, status: Int = 200) = route(path) { respond(it, status, body) }

    fun redirect(path: String, to: String, status: Int = 302) = route(path) {
        it.responseHeaders.add("Location", to)
        it.sendResponseHeaders(status, -1)
    }

    /** Waits [millis] before sending anything (a server that accepts the connection and then goes quiet). */
    fun delayed(path: String, millis: Long, body: String) = route(path) {
        Thread.sleep(millis)
        respond(it, 200, body)
    }

    /** Sends [size] bytes of 'x'. */
    fun bytes(path: String, size: Int) = route(path) {
        it.sendResponseHeaders(200, size.toLong())
        val chunk = ByteArray(8 * 1024) { 'x'.code.toByte() }
        var left = size
        while (left > 0) {
            val n = minOf(chunk.size, left)
            it.responseBody.write(chunk, 0, n)
            left -= n
        }
    }

    /** Announces a large body, sends [firstBytes] bytes, then stalls for [stallMillis]. */
    fun stall(path: String, firstBytes: Int, stallMillis: Long) = route(path) {
        it.sendResponseHeaders(200, 10_000_000L)
        it.responseBody.write(ByteArray(firstBytes) { 'x'.code.toByte() })
        it.responseBody.flush()
        Thread.sleep(stallMillis)
    }

    override fun close() {
        server.stop(0)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}
