package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.AdbDevice
import com.titanicbhai.uiscope.ports.TrackerEvent
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches the device list. Preferred: the adb server's `host:track-devices` service (length-prefixed messages over a local socket),
 * which pushes a message the moment a device appears, disappears or changes state. Each message triggers [refresh] (normally
 * `adb devices -l`) for the details. If the socket cannot be used, the list is polled every 3 s; after three consecutive failures the
 * wait doubles (up to 30 s) instead of spinning. Cancelling the collector closes the socket.
 */
class DeviceTracker(
    private val refresh: suspend () -> Outcome<List<AdbDevice>>,
    private val dispatchers: DispatcherProvider,
    private val host: String = "127.0.0.1",
    private val port: Int = 5037,
    private val startServer: suspend () -> Unit = {},
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val logger: Logger = Logger.getLogger("uiscope.adb"),
) {
    fun track(): Flow<TrackerEvent> = flow {
        var failures = 0
        while (coroutineContext.isActive) {
            var connected = false
            try {
                trackOnce(onConnected = { connected = true; failures = 0 }) { emit(toEvent(refresh())) }
            } catch (e: IOException) {
                logger.log(Level.FINE, "adb track-devices ended", e)
            }
            if (!connected) {
                failures++
                if (failures == 1) startServer()
                emit(toEvent(refresh())) // polling fallback
            }
            delayFn(waitMs(failures))
        }
    }

    private fun toEvent(result: Outcome<List<AdbDevice>>): TrackerEvent = when (result) {
        is Outcome.Success -> TrackerEvent.Devices(result.value)
        is Outcome.Failure -> TrackerEvent.Problem(result.error)
    }

    private suspend fun trackOnce(onConnected: () -> Unit, onChange: suspend () -> Unit) {
        val socket = Socket()
        try {
            coroutineScope {
                // A blocked read cannot be cancelled, so a watcher closes the socket when the collector goes away.
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        closeQuietly(socket)
                    }
                }
                try {
                    withContext(dispatchers.io) {
                        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                        val out = socket.getOutputStream()
                        val input = DataInputStream(socket.getInputStream())
                        val request = "host:track-devices"
                        out.write("%04x%s".format(request.length, request).toByteArray(Charsets.US_ASCII))
                        out.flush()
                        val status = ByteArray(4)
                        input.readFully(status)
                        if (String(status, Charsets.US_ASCII) != "OKAY") throw IOException("adb refused track-devices")
                        onConnected()
                    }
                    while (true) {
                        withContext(dispatchers.io) { readMessage(DataInputStream(socket.getInputStream())) }
                        onChange()
                    }
                } finally {
                    watcher.cancel()
                    closeQuietly(socket)
                }
            }
        } finally {
            closeQuietly(socket)
        }
    }

    private fun readMessage(input: DataInputStream) {
        val header = ByteArray(4)
        input.readFully(header)
        val length = String(header, Charsets.US_ASCII).toIntOrNull(16) ?: throw IOException("bad message length from adb")
        input.readFully(ByteArray(length)) // the content is only a trigger; the details come from `devices -l`
    }

    private fun waitMs(failures: Int): Long =
        if (failures <= FREE_FAILURES) POLL_MS else minOf(POLL_MS shl (failures - FREE_FAILURES).coerceAtMost(8), MAX_WAIT_MS)

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: IOException) {
            logger.log(Level.FINE, "socket close failed", e)
        }
    }

    companion object {
        const val POLL_MS = 3_000L
        const val MAX_WAIT_MS = 30_000L
        private const val FREE_FAILURES = 3
        private const val CONNECT_TIMEOUT_MS = 2_000
    }
}
