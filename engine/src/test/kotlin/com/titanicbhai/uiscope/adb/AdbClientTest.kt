package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.AdbDevice
import com.titanicbhai.uiscope.model.DeviceState
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.testing.FakeProcessRunner
import com.titanicbhai.uiscope.testing.Fixtures
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.blocking
import com.titanicbhai.uiscope.testing.withTempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AdbClientTest {
    private val device = AdbDevice("R58M12ABCDE", DeviceState.DEVICE, "SM_G991B")

    private fun png(uniform: Boolean): ByteArray {
        val image = BufferedImage(120, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) for (x in 0 until 120) image.setRGB(x, y, if (uniform) 0x202020 else (x * 2 shl 16) or (y shl 8))
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** Answers like a phone. [dumps] maps a dump command (joined args after the serial) to its successive answers. */
    private class Phone(
        val dumps: MutableMap<String, MutableList<Outcome<com.titanicbhai.uiscope.process.ProcessResult>>> = HashMap(),
        var screenshot: Outcome<com.titanicbhai.uiscope.process.ProcessResult>,
    ) {
        val props = mapOf("ro.product.model" to "SM_G991B", "ro.build.version.release" to "14", "ro.build.version.sdk" to "34")
        val runner = FakeProcessRunner { cmd ->
            val args = cmd.drop(3).joinToString(" ") // adb -s SERIAL …
            when {
                cmd.size >= 4 && cmd[3] == "shell" && cmd[4] == "getprop" -> FakeProcessRunner.ok(props[cmd[5]].orEmpty() + "\n")
                args == "exec-out screencap -p" -> screenshot
                args.startsWith("shell rm") -> FakeProcessRunner.ok()
                else -> dumps[args]?.let { if (it.size > 1) it.removeAt(0) else it.first() }
            }
        }
    }

    private fun ok(text: String) = FakeProcessRunner.ok(text)

    private fun client(dir: Path, phone: Phone, delays: MutableList<Long> = CopyOnWriteArrayList()) =
        AdbClient("adb", phone.runner, dir, StandardDispatcherProvider(Dispatchers.Unconfined), delayFn = { delays += it })

    private val stdout = "exec-out uiautomator dump /dev/stdout"
    private val tty = "exec-out uiautomator dump /dev/tty"
    private val fileDump = "shell uiautomator dump /data/local/tmp/uiscope-dump.xml"
    private val cat = "exec-out cat /data/local/tmp/uiscope-dump.xml"

    private fun happy(vararg extra: Pair<String, String>, shot: ByteArray = png(false)) =
        Phone(mutableMapOf(stdout to mutableListOf(ok(Fixtures.androidDump("g1-trailing-suffix.xml")))).also { m -> extra.forEach { m[it.first] = mutableListOf(ok(it.second)) } }, FakeProcessRunner.bytes(shot))

    @Test
    fun `devices parses the listing, keeps the version signal and uses a 5 second timeout`() = blocking {
        val runner = FakeProcessRunner { FakeProcessRunner.ok(Fixtures.text("adb/devices-g9.txt")) }
        val listing = AdbClient("adb", runner, Path.of("."), StandardDispatcherProvider(Dispatchers.Unconfined)).devices().assertSuccess()
        assertEquals(6, listing.devices.size)
        assertEquals(listOf<AdbSignal>(AdbSignal.VersionMismatch(41, 40)), listing.signals)
        assertEquals(listOf("adb", "devices", "-l"), runner.calls.single())
        assertEquals(5.seconds, runner.timeouts.single())
    }

    @Test
    fun `a dead adb server is a typed failure and a missing adb is ADB_NOT_FOUND`() = blocking {
        val dead = FakeProcessRunner { FakeProcessRunner.ok("", "adb: error: cannot connect to daemon at tcp:5037: Connection refused", 1) }
        AdbClient("adb", dead, Path.of("."), StandardDispatcherProvider(Dispatchers.Unconfined)).devices().assertFailure(ErrorCodes.ADB_START_FAILED)
        val missing = FakeProcessRunner { null }
        AdbClient("adb", missing, Path.of("."), StandardDispatcherProvider(Dispatchers.Unconfined)).devices().assertFailure(ErrorCodes.ADB_NOT_FOUND)
    }

    @Test
    fun `restart kills then starts the server`() = blocking {
        val runner = FakeProcessRunner { FakeProcessRunner.ok("* daemon started successfully") }
        AdbClient("adb", runner, Path.of("."), StandardDispatcherProvider(Dispatchers.Unconfined)).restartServer().assertSuccess()
        assertEquals(listOf(listOf("adb", "kill-server"), listOf("adb", "start-server")), runner.calls.toList())
        assertEquals(15.seconds, runner.timeouts.last())
    }

    @Test
    fun `start server runs adb start-server and reports a daemon that cannot start`() = blocking {
        val ok = FakeProcessRunner { FakeProcessRunner.ok("* daemon not running; starting now at tcp:5037\n* daemon started successfully") }
        AdbClient("adb", ok, Path.of("."), StandardDispatcherProvider(Dispatchers.Unconfined)).startServer().assertSuccess()
        assertEquals(listOf(listOf("adb", "start-server")), ok.calls.toList())
        assertEquals(15.seconds, ok.timeouts.single())
        val bad = FakeProcessRunner { FakeProcessRunner.ok("", "* failed to start daemon\nadb: error: could not install *smartsocket* listener: Address already in use", 1) }
        AdbClient("adb", bad, Path.of("."), StandardDispatcherProvider(Dispatchers.Unconfined)).startServer().assertFailure(ErrorCodes.ADB_START_FAILED)
    }

    @Test
    fun `a normal capture has the tree, the screenshot, the device and the timeouts of the plan`() = blocking {
        withTempDir { dir ->
            val phone = happy()
            val c = client(dir, phone).capture(device).assertSuccess()
            assertEquals(2, c.roots.sumOf { 1 + it.children.size })
            assertEquals(120, c.screenshot!!.width)
            assertEquals(240, c.screenshot!!.height)
            assertTrue(Files.size(Path.of(c.screenshot!!.file)) > 100)
            val target = c.target as Target.Android
            assertEquals("SM_G991B", target.device.model)
            assertEquals("14", target.device.androidVersion)
            assertEquals(34, target.device.sdkInt)
            assertEquals("com.app", target.foregroundPackage)
            assertTrue(c.warnings.isEmpty(), c.warnings.toString())
            val dumpIndex = phone.runner.calls.indexOfFirst { it.joinToString(" ").endsWith("uiautomator dump /dev/stdout") }
            assertEquals(20.seconds, phone.runner.timeouts[dumpIndex])
            val shotIndex = phone.runner.calls.indexOfFirst { it.last() == "-p" }
            assertEquals(10.seconds, phone.runner.timeouts[shotIndex])
        }
    }

    @Test
    fun `strategies fall back in order and the winner is remembered per device`() = blocking {
        withTempDir { dir ->
            val phone = Phone(
                mutableMapOf(stdout to mutableListOf(ok("")), tty to mutableListOf(ok("")), fileDump to mutableListOf(ok("UI hierchary dumped to: /data/local/tmp/uiscope-dump.xml")), cat to mutableListOf(ok(Fixtures.androidDump("g1-clean.xml")))),
                FakeProcessRunner.bytes(png(false)),
            )
            val client = client(dir, phone)
            client.capture(device).assertSuccess()
            val dumpCalls = phone.runner.calls.map { it.drop(3).joinToString(" ") }.filter { it.contains("dump") || it.contains("cat") || it.startsWith("shell rm") }
            assertEquals(listOf(stdout, tty, fileDump, cat, "shell rm -f /data/local/tmp/uiscope-dump.xml"), dumpCalls)
            phone.runner.calls.clear()
            client.capture(device).assertSuccess()
            val second = phone.runner.calls.map { it.drop(3).joinToString(" ") }.filter { it.contains("uiautomator dump") }
            assertEquals(fileDump, second.first(), "the winning strategy goes first next time")
            assertEquals(1, second.size)
        }
    }

    @Test
    fun `a null root is retried twice and then reported`() = blocking {
        withTempDir { dir ->
            val phone = Phone(mutableMapOf(stdout to mutableListOf(ok(Fixtures.androidDump("error-null-root.txt")))), FakeProcessRunner.bytes(png(false)))
            val delays = CopyOnWriteArrayList<Long>()
            client(dir, phone, delays).capture(device).assertFailure(ErrorCodes.DUMP_NULL_ROOT)
            assertEquals(3, phone.runner.calls.count { it.joinToString(" ").endsWith(stdout) })
            assertEquals(listOf(300L, 300L), delays.toList())
            assertEquals(0, phone.runner.calls.count { it.joinToString(" ").endsWith(tty) }, "other strategies cannot fix a null root")
        }
    }

    @Test
    fun `a null root that clears up on the second try is a success`() = blocking {
        withTempDir { dir ->
            val phone = Phone(mutableMapOf(stdout to mutableListOf(ok("ERROR: null root node returned by UiTestAutomationBridge.\n"), ok(Fixtures.androidDump("g1-trailing-suffix.xml")))), FakeProcessRunner.bytes(png(false)))
            client(dir, phone).capture(device).assertSuccess()
        }
    }

    @Test
    fun `device and adb problems are reported without trying other strategies`() = blocking {
        withTempDir { dir ->
            val unauthorized = Phone(mutableMapOf(stdout to mutableListOf(FakeProcessRunner.ok("", "error: device unauthorized.\n", 1))), FakeProcessRunner.ok("", "error: device unauthorized.", 1))
            client(dir, unauthorized).capture(device).assertFailure(ErrorCodes.DEVICE_UNAUTHORIZED)
            assertEquals(0, unauthorized.runner.calls.count { it.joinToString(" ").endsWith(tty) })
            val timeout = Phone(mutableMapOf(stdout to mutableListOf(FakeProcessRunner.timedOut())), FakeProcessRunner.bytes(png(false)))
            client(dir, timeout).capture(device).assertFailure(ErrorCodes.DUMP_TIMEOUT)
            val busy = Phone(mutableMapOf(stdout to mutableListOf(ok("java.lang.IllegalStateException: UiAutomation not connected"))), FakeProcessRunner.bytes(png(false)))
            client(dir, busy).capture(device).assertFailure(ErrorCodes.UIAUTOMATOR_BUSY)
        }
    }

    @Test
    fun `a screenshot problem is a warning and the tree is still delivered`() = blocking {
        withTempDir { dir ->
            val c = client(dir, happy(shot = "this is not a png at all, just text".toByteArray())).capture(device).assertSuccess()
            assertNull(c.screenshot)
            assertEquals(listOf(ErrorCodes.SCREENSHOT_FAILED), c.warnings.map { it.code })
            val timedOut = Phone(mutableMapOf(stdout to mutableListOf(ok(Fixtures.androidDump("g1-clean.xml")))), FakeProcessRunner.timedOut())
            assertEquals(listOf(ErrorCodes.SCREENSHOT_FAILED), client(dir, timedOut).capture(device).assertSuccess().warnings.map { it.code })
        }
    }

    @Test
    fun `a single colour screenshot raises the secure window warning`() = blocking {
        withTempDir { dir ->
            val c = client(dir, happy(shot = png(uniform = true))).capture(device).assertSuccess()
            assertEquals(listOf(ErrorCodes.SECURE_WINDOW), c.warnings.map { it.code })
            assertNotNull(c.screenshot)
        }
    }

    @Test
    fun `the device file is removed even when reading it fails`() = blocking {
        withTempDir { dir ->
            val phone = Phone(
                mutableMapOf(stdout to mutableListOf(ok("")), tty to mutableListOf(ok("")), fileDump to mutableListOf(ok("dumped")), cat to mutableListOf(FakeProcessRunner.timedOut())),
                FakeProcessRunner.bytes(png(false)),
            )
            client(dir, phone).capture(device).assertFailure(ErrorCodes.DUMP_TIMEOUT)
            assertTrue(phone.runner.calls.any { it.joinToString(" ").endsWith("shell rm -f /data/local/tmp/uiscope-dump.xml") })
        }
    }
}
