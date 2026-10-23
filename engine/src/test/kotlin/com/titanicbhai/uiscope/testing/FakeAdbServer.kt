package com.titanicbhai.uiscope.testing

import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.logging.Level
import java.util.logging.Logger

/** Speaks the server side of adb's smart-socket protocol on a local port: `host:track-devices` gets OKAY, an initial (empty) list, then whatever [push] sends. */
class FakeAdbServer(private val reply: String = "OKAY") : AutoCloseable {
    private val logger = Logger.getLogger("uiscope.test.fakeadb")
    private val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
    private val outgoing = LinkedBlockingQueue<String>()
    val requests = CopyOnWriteArrayList<String>()
    val port: Int get() = server.localPort

    init {
        Thread({ serve() }, "fake-adb").apply { isDaemon = true }.start()
    }

    /** Sends one length-prefixed message to the connected client. */
    fun push(payload: String) {
        outgoing.put("%04x%s".format(payload.length, payload))
    }

    private fun serve() {
        try {
            while (!server.isClosed) handle(server.accept())
        } catch (e: IOException) {
            logger.log(Level.FINE, "fake adb stopped", e)
        }
    }

    private fun handle(socket: Socket) {
        try {
            val input = DataInputStream(socket.getInputStream())
            val header = ByteArray(4)
            input.readFully(header)
            val payload = ByteArray(String(header).toInt(16)).also { input.readFully(it) }
            requests += String(payload)
            val out = socket.getOutputStream()
            out.write(reply.toByteArray())
            out.flush()
            if (reply != "OKAY") return
            out.write("0000".toByteArray()) // like the real server: the current device list (empty here) follows OKAY at once
            out.flush()
            while (!socket.isClosed) {
                val message = outgoing.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                out.write(message.toByteArray())
                out.flush()
            }
        } catch (e: IOException) {
            logger.log(Level.FINE, "fake adb client left", e)
        } finally {
            socket.close()
        }
    }

    override fun close() {
        server.close()
    }
}
