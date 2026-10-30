package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.HostOs
import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.model.AdbDevice
import com.titanicbhai.uiscope.model.DeviceState
import com.titanicbhai.uiscope.ports.TrackerEvent
import com.titanicbhai.uiscope.storage.DataPaths
import com.titanicbhai.uiscope.testing.FakeAdbServer
import com.titanicbhai.uiscope.testing.FakeProcessRunner
import com.titanicbhai.uiscope.testing.Fixtures
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.blocking
import com.titanicbhai.uiscope.testing.withTempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineAdbPortTest {
    private val dispatchers = StandardDispatcherProvider(Dispatchers.Unconfined)

    // The simulated platform must match the real host: with the LINUX separator on a Windows host, the
    // ":" split of the PATH entry would also cut at the temp dir's drive letter and adb would not be found.
    private val host = HostOs.current()
    private val exe = if (host == HostOs.WINDOWS) "adb.exe" else "adb"
    private val sep = if (host == HostOs.WINDOWS) ";" else ":"

    private fun png(): ByteArray {
        val image = BufferedImage(60, 120, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 120) for (x in 0 until 60) image.setRGB(x, y, (x * 4 shl 16) or (y * 2 shl 8))
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun phoneRunner(adbPath: String) = FakeProcessRunner { cmd ->
        val args = cmd.drop(1).joinToString(" ")
        when {
            cmd[0] == adbPath && args == "version" -> FakeProcessRunner.ok("Android Debug Bridge version 1.0.41\nVersion 35.0.0-1\n")
            args == "devices -l" -> FakeProcessRunner.ok("List of devices attached\nPHONE1 device usb:1-1 product:p model:Pixel_7 device:d transport_id:1\n")
            args.endsWith("uiautomator dump /dev/stdout") -> FakeProcessRunner.ok(Fixtures.androidDump("g1-trailing-suffix.xml"))
            args.endsWith("exec-out screencap -p") -> FakeProcessRunner.bytes(png())
            args.contains("getprop") -> FakeProcessRunner.ok("x\n")
            else -> null
        }
    }

    @Test
    fun `locate then capture and track go through the real engine classes`() = blocking {
        withTempDir { dir ->
            val adb = dir.resolve("bin/$exe")
            Files.createDirectories(adb.parent)
            Files.writeString(adb, "binary")
            val runner = phoneRunner(adb.toString())
            FakeAdbServer().use { server ->
                val locator = AdbLocator(runner, FileAdbPathStore(dir.resolve("none.txt")), DataPaths(dir.resolve("data")), host, mapOf("PATH" to adb.parent.toString()), dir.resolve("home"), sep)
                val port = EngineAdbPort(locator, runner, DataPaths(dir.resolve("data")), dispatchers, serverPort = server.port)

                val status = port.locate().assertSuccess()
                assertEquals(adb.toString(), status.path)
                assertEquals("1.0.41 (35.0.0-1)", status.version)
                assertEquals("PATH", status.source)

                val event = withTimeout(20_000) { port.trackDevices().first() }
                val devices = assertIs<TrackerEvent.Devices>(event).devices
                assertEquals(listOf(AdbDevice("PHONE1", DeviceState.DEVICE, "Pixel_7", "p", "1", com.titanicbhai.uiscope.model.Connection.USB)), devices)

                val capture = port.capture(devices.single()).assertSuccess()
                assertEquals(2, capture.roots.sumOf { 1 + it.children.size })
                assertEquals(60, capture.screenshot!!.width)
            }
        }
    }

    @Test
    fun `before adb is located everything reports ADB_NOT_FOUND`() = blocking {
        withTempDir { dir ->
            val runner = FakeProcessRunner { null }
            val locator = AdbLocator(runner, FileAdbPathStore(dir.resolve("none.txt")), DataPaths(dir.resolve("data")), host, emptyMap(), dir.resolve("home"), sep)
            val port = EngineAdbPort(locator, runner, DataPaths(dir.resolve("data")), dispatchers)
            port.locate().assertFailure(ErrorCodes.ADB_NOT_FOUND)
            val event = withTimeout(10_000) { port.trackDevices().first() }
            assertTrue(event is TrackerEvent.Problem && event.error.code == ErrorCodes.ADB_NOT_FOUND)
            port.capture(AdbDevice("X", DeviceState.DEVICE)).assertFailure(ErrorCodes.ADB_NOT_FOUND)
        }
    }
}
