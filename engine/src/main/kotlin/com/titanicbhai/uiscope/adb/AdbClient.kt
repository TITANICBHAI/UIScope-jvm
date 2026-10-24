package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.error.Remedy
import com.titanicbhai.uiscope.model.AdbDevice
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.CaptureWarning
import com.titanicbhai.uiscope.model.DeviceInfo
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ScreenInfo
import com.titanicbhai.uiscope.model.Screenshot
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.process.ProcessResult
import com.titanicbhai.uiscope.process.ProcessRunner
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Everything the app asks adb. Each call is one `ProcessRunner` invocation with its own timeout (plan §4.3):
 * version 5 s, devices 5 s, getprop 5 s, dump 20 s, screencap 10 s. Never logs output, only exit codes and durations (via the runner).
 */
class AdbClient(
    private val adb: String,
    private val runner: ProcessRunner,
    private val tmpDir: Path,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val logger: Logger = Logger.getLogger("uiscope.adb"),
) {
    private val infoCache = ConcurrentHashMap<String, DeviceInfo>()
    private val winningStrategy = ConcurrentHashMap<String, Int>()

    suspend fun version(): Outcome<AdbVersion> = when (val r = run(null, 5.seconds, ErrorCodes.ADB_START_FAILED, "version")) {
        is Outcome.Failure -> r
        is Outcome.Success -> AdbParsers.parseVersion(r.value.stdoutText())?.let { Outcome.Success(it) } ?: fail(ErrorCodes.ADB_NOT_FOUND, "That program did not identify itself as adb")
    }

    suspend fun devices(): Outcome<DeviceListing> {
        val r = when (val x = run(null, 5.seconds, ErrorCodes.ADB_START_FAILED, "devices", "-l")) {
            is Outcome.Failure -> return x
            is Outcome.Success -> x.value
        }
        val text = r.stdoutText() + "\n" + r.stderrText()
        val listing = AdbParsers.parseDevices(text)
        // A version-mismatch warning is a signal, not a failure: adb restarts its server and still lists the devices.
        if (listing.devices.isEmpty() && listing.signals.isEmpty()) {
            AdbErrors.classify(text)?.takeIf { it.code == ErrorCodes.ADB_START_FAILED }?.let { return Outcome.Failure(it) }
        }
        return Outcome.Success(listing)
    }

    /** `adb start-server`: what the device tracker calls when the server is not running. Starting a running server is harmless. */
    suspend fun startServer(): Outcome<Unit> =
        when (val r = run(null, 15.seconds, ErrorCodes.ADB_START_FAILED, "start-server")) {
            is Outcome.Failure -> r
            is Outcome.Success -> AdbErrors.classify(r.value.stdoutText() + "\n" + r.value.stderrText())?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)
        }

    suspend fun restartServer(): Outcome<Unit> {
        run(null, 5.seconds, ErrorCodes.ADB_START_FAILED, "kill-server")
        return when (val r = run(null, 15.seconds, ErrorCodes.ADB_START_FAILED, "start-server")) {
            is Outcome.Failure -> r
            is Outcome.Success -> AdbErrors.classify(r.value.stdoutText() + "\n" + r.value.stderrText())?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)
        }
    }

    /** Model, Android version and API level, fetched once per device. Missing values are simply null. */
    suspend fun deviceInfo(device: AdbDevice): DeviceInfo {
        infoCache[device.serial]?.let { return it }
        suspend fun prop(key: String): String? =
            ((run(device.serial, 5.seconds, ErrorCodes.ADB_START_FAILED, "shell", "getprop", key) as? Outcome.Success)?.value?.stdoutText())?.trim()?.ifEmpty { null }
        val model = prop("ro.product.model") ?: device.model
        val release = prop("ro.build.version.release")
        val sdk = prop("ro.build.version.sdk")?.toIntOrNull()
        val info = DeviceInfo(device.serial, model, release, sdk)
        if (release != null || model != null) infoCache[device.serial] = info
        return info
    }

    // ── capture ────────────────────────────────────────────────────────────────────────────────

    /** The tree and the screenshot, fetched at the same time and published together. A missing screenshot is a warning; a missing tree is the error. */
    suspend fun capture(device: AdbDevice): Outcome<Capture> = coroutineScope {
        val dump = async { dump(device.serial) }
        val shot = async { screenshot(device.serial) }
        val info = async { deviceInfo(device) }
        val parsed = when (val d = dump.await()) {
            is Outcome.Failure -> {
                shot.cancel()
                info.cancel()
                return@coroutineScope d
            }
            is Outcome.Success -> d.value
        }
        val id = UUID.randomUUID().toString()
        val warnings = ArrayList(parsed.warnings)
        var screen = parsed.screen
        var screenshot: Screenshot? = null
        when (val s = shot.await()) {
            is Outcome.Failure -> warnings += CaptureWarning(ErrorCodes.SCREENSHOT_FAILED, s.error.detail)
            is Outcome.Success -> {
                val png = s.value
                val file = writeScreenshot(id, png.bytes)
                if (file != null) {
                    screenshot = Screenshot(file.toString(), png.width, png.height)
                    if (parsed.screen.width <= 0 || parsed.screen.height <= 0) screen = ScreenInfo(png.width, png.height, 0, 0, parsed.screen.rotation)
                    if (png.uniform && warnings.none { it.code == ErrorCodes.SECURE_WINDOW }) {
                        warnings += CaptureWarning(ErrorCodes.SECURE_WINDOW, "The screenshot is a single colour: the app may block screen capture")
                    }
                } else {
                    warnings += CaptureWarning(ErrorCodes.SCREENSHOT_FAILED, "The screenshot could not be saved")
                }
            }
        }
        Outcome.Success(
            Capture(
                id = id,
                takenAt = clock.instant(),
                target = Target.Android(info.await(), foregroundPackage(parsed.roots)),
                screen = screen,
                screenshot = screenshot,
                roots = parsed.roots,
                warnings = warnings,
            ),
        )
    }

    /** The package of the first window that is not system UI: the app window comes first, dialogs and overlays after it. */
    private fun foregroundPackage(roots: List<ElementNode>): String? =
        roots.mapNotNull { it.properties["package"] }.firstOrNull { !it.startsWith("com.android.systemui") } ?: roots.firstNotNullOfOrNull { it.properties["package"] }

    private val strategies: List<suspend (String) -> Outcome<String>> = listOf(
        { serial -> text(serial, 20.seconds, ErrorCodes.DUMP_TIMEOUT, "exec-out", "uiautomator", "dump", "/dev/stdout") },
        { serial -> text(serial, 20.seconds, ErrorCodes.DUMP_TIMEOUT, "exec-out", "uiautomator", "dump", "/dev/tty") },
        { serial -> viaFile(serial) },
    )

    private suspend fun dump(serial: String): Outcome<ParseResult> {
        val first = winningStrategy[serial] ?: 0
        val order = listOf(first) + strategies.indices.filter { it != first }
        var last: AppError = CodedError(ErrorCodes.PARSE_FAILED, "No dump strategy produced a hierarchy")
        for (index in order) {
            var attempt = 0
            while (true) {
                val raw = when (val r = strategies[index](serial)) {
                    is Outcome.Failure -> return r // device or adb problem: another strategy cannot fix it
                    is Outcome.Success -> r.value
                }
                when (val parsed = UiAutomatorParser.parse(raw)) {
                    is Outcome.Success -> {
                        winningStrategy[serial] = index
                        return parsed
                    }
                    is Outcome.Failure -> {
                        val code = parsed.error.code
                        if ((code == ErrorCodes.DUMP_NULL_ROOT || code == ErrorCodes.DUMP_IDLE_TIMEOUT) && attempt < RETRIES) {
                            attempt++
                            delayFn(RETRY_DELAY_MS)
                            continue
                        }
                        if (code != ErrorCodes.PARSE_FAILED) return parsed // null root, idle, busy: the same on every strategy
                        last = parsed.error
                        break // this strategy produced no hierarchy: try the next one
                    }
                }
            }
        }
        return Outcome.Failure(last)
    }

    /** Strategy 3: dump to a file on the device, read it, delete it (cleanup runs even when the capture is cancelled). */
    private suspend fun viaFile(serial: String): Outcome<String> {
        val remote = "/data/local/tmp/uiscope-dump.xml"
        try {
            when (val d = text(serial, 20.seconds, ErrorCodes.DUMP_TIMEOUT, "shell", "uiautomator", "dump", remote)) {
                is Outcome.Failure -> return d
                is Outcome.Success -> if (d.value.contains("ERROR", ignoreCase = true) && !d.value.contains("<hierarchy")) return Outcome.Success(d.value)
            }
            return text(serial, 10.seconds, ErrorCodes.DUMP_TIMEOUT, "exec-out", "cat", remote)
        } finally {
            withContext(NonCancellable) { run(serial, 5.seconds, ErrorCodes.DUMP_TIMEOUT, "shell", "rm", "-f", remote) }
        }
    }

    private class Png(val bytes: ByteArray, val width: Int, val height: Int, val uniform: Boolean)

    private suspend fun screenshot(serial: String): Outcome<Png> {
        val r = when (val x = run(serial, 10.seconds, ErrorCodes.SCREENSHOT_FAILED, "exec-out", "screencap", "-p")) {
            is Outcome.Failure -> return x
            is Outcome.Success -> x.value
        }
        val bytes = r.stdout
        if (!isPng(bytes)) return fail(ErrorCodes.SCREENSHOT_FAILED, "adb did not return a PNG image (${bytes.size} bytes)")
        val width = readInt(bytes, 16)
        val height = readInt(bytes, 20)
        return Outcome.Success(Png(bytes, width, height, withContext(dispatchers.default) { isUniform(bytes) }))
    }

    private fun writeScreenshot(id: String, bytes: ByteArray): Path? =
        try {
            Files.createDirectories(tmpDir)
            Files.write(tmpDir.resolve("$id.png"), bytes)
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not save the screenshot", e)
            null
        }

    private fun isPng(b: ByteArray) = b.size > 24 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    private fun readInt(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

    /** True when a grid of sample pixels is one colour (a blank, locked or protected screen). Undecodable images are not called uniform. */
    private fun isUniform(bytes: ByteArray): Boolean {
        val image: BufferedImage = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (e: IOException) {
            logger.log(Level.FINE, "screenshot could not be decoded for the uniform-colour check", e)
            null
        } ?: return false
        val first = image.getRGB(0, 0)
        val stepX = (image.width / GRID).coerceAtLeast(1)
        val stepY = (image.height / GRID).coerceAtLeast(1)
        for (y in 0 until image.height step stepY) for (x in 0 until image.width step stepX) if (image.getRGB(x, y) != first) return false
        return true
    }

    // ── plumbing ───────────────────────────────────────────────────────────────────────────────

    private suspend fun text(serial: String?, timeout: Duration, timeoutCode: String, vararg args: String): Outcome<String> =
        when (val r = run(serial, timeout, timeoutCode, *args)) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                val out = r.value.stdoutText()
                val err = r.value.stderrText()
                // adb's own errors win, but only when there is no tree in the output (a tree may contain any text)
                val problem = if (out.contains("<hierarchy")) null else AdbErrors.classify(err + "\n" + out.take(2_000))
                if (problem != null) Outcome.Failure(problem) else Outcome.Success(out + err.takeIf { out.isEmpty() }.orEmpty())
            }
        }

    private suspend fun run(serial: String?, timeout: Duration, timeoutCode: String, vararg args: String): Outcome<ProcessResult> {
        val cmd = ArrayList<String>().apply {
            add(adb)
            if (serial != null) {
                add("-s")
                add(serial)
            }
            addAll(args)
        }
        return when (val r = runner.run(cmd, timeout)) {
            is Outcome.Success -> r
            is Outcome.Failure -> when (r.error.code) {
                ErrorCodes.PROCESS_TIMEOUT -> Outcome.Failure(CodedError(timeoutCode, "adb did not answer within $timeout", Remedy.RETRY))
                ErrorCodes.PROCESS_START_FAILED -> Outcome.Failure(CodedError(ErrorCodes.ADB_NOT_FOUND, "adb could not be started", Remedy.RETRY))
                else -> r
            }
        }
    }

    private fun fail(code: String, detail: String) = Outcome.Failure(CodedError(code, detail, Remedy.RETRY))

    private companion object {
        const val RETRIES = 2
        const val RETRY_DELAY_MS = 300L
        const val GRID = 48
    }
}
