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
 * version 15 s, devices 15 s, getprop 5 s, dump 20 s, screencap 20 s. Never logs output, only exit codes and durations (via the runner).
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
    private val winningScreen = ConcurrentHashMap<String, Int>()

    suspend fun version(): Outcome<AdbVersion> = when (val r = run(null, 15.seconds, ErrorCodes.ADB_START_FAILED, "version")) {
        is Outcome.Failure -> r
        is Outcome.Success -> AdbParsers.parseVersion(r.value.stdoutText())?.let { Outcome.Success(it) } ?: fail(ErrorCodes.ADB_NOT_FOUND, "That program did not identify itself as adb")
    }

    suspend fun devices(): Outcome<DeviceListing> {
        val r = when (val x = run(null, 15.seconds, ErrorCodes.ADB_START_FAILED, "devices", "-l")) {
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

    /** `adb start-server`: what the device tracker calls when the server is not running. Starting a running server is harmless.
     *  The 60 s budget covers a cold start while antivirus scans the fresh adb.exe — a timeout here kills the whole tree,
     *  including the server that was starting, which is how a machine can loop on NO_DEVICE forever. */
    suspend fun startServer(): Outcome<Unit> =
        when (val r = run(null, 60.seconds, ErrorCodes.ADB_START_FAILED, "start-server")) {
            is Outcome.Failure -> r
            is Outcome.Success -> AdbErrors.classify(r.value.stdoutText() + "\n" + r.value.stderrText())?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)
        }

    suspend fun restartServer(): Outcome<Unit> {
        run(null, 5.seconds, ErrorCodes.ADB_START_FAILED, "kill-server")
        return when (val r = run(null, 60.seconds, ErrorCodes.ADB_START_FAILED, "start-server")) {
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

    /**
     * The tree and the screenshot, fetched at the same time. The picture is the point of the app, so it never dies with the tree: when
     * only the element dump fails (an animating screen, a video, a game, an app that blocks accessibility) the capture still succeeds with
     * the picture, no elements and a warning that says why. [onScreen] is called with a picture-only capture the moment the screenshot is
     * on disk, usually seconds before the dump is done, so the screen is on screen as early as it can be.
     */
    suspend fun capture(device: AdbDevice, onScreen: (Capture) -> Unit = {}): Outcome<Capture> = coroutineScope {
        val id = UUID.randomUUID().toString()
        val dump = async { dump(device.serial) }
        val shot = async { fetchScreen(id, device, onScreen) }
        val info = async { deviceInfo(device) }
        val parsed = when (val d = dump.await()) {
            is Outcome.Failure -> {
                val picture = if (d.error.code in TREE_ONLY_FAILURES) shot.await() else null
                if (picture !is Shot.Got) {
                    shot.cancel()
                    info.cancel()
                    return@coroutineScope d
                }
                return@coroutineScope Outcome.Success(pictureOnly(id, info.await(), picture, d.error))
            }
            is Outcome.Success -> d.value
        }
        val warnings = ArrayList(parsed.warnings)
        var screen = parsed.screen
        var screenshot: Screenshot? = null
        when (val s = shot.await()) {
            is Shot.Missing -> warnings += CaptureWarning(ErrorCodes.SCREENSHOT_FAILED, s.error.detail)
            is Shot.Got -> {
                screenshot = s.screenshot
                if (parsed.screen.width <= 0 || parsed.screen.height <= 0) {
                    screen = ScreenInfo(s.screenshot.width, s.screenshot.height, 0, 0, parsed.screen.rotation)
                }
                if (s.uniform && warnings.none { it.code == ErrorCodes.SECURE_WINDOW }) warnings += CaptureWarning(ErrorCodes.SECURE_WINDOW, UNIFORM_DETAIL)
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

    private sealed interface Shot {
        class Got(val screenshot: Screenshot, val uniform: Boolean) : Shot

        class Missing(val error: AppError) : Shot
    }

    /** Takes the screenshot, saves it and tells [onScreen] about it straight away. */
    private suspend fun fetchScreen(id: String, device: AdbDevice, onScreen: (Capture) -> Unit): Shot {
        val png = when (val s = screenshot(device.serial)) {
            is Outcome.Failure -> return Shot.Missing(s.error)
            is Outcome.Success -> s.value
        }
        val file = writeScreenshot(id, png.bytes)
            ?: return Shot.Missing(CodedError(ErrorCodes.SCREENSHOT_FAILED, "The screenshot could not be saved", Remedy.RETRY))
        val screenshot = Screenshot(file.toString(), png.width, png.height)
        val early = Capture(
            id = id,
            takenAt = clock.instant(),
            target = Target.Android(DeviceInfo(device.serial, device.model)),
            screen = ScreenInfo(png.width, png.height),
            screenshot = screenshot,
            roots = emptyList(),
        )
        try {
            onScreen(early)
        } catch (e: RuntimeException) {
            logger.log(Level.WARNING, "the early screen callback failed", e)
        }
        return Shot.Got(screenshot, png.uniform)
    }

    /** What a capture is when the picture came but the element tree did not: the screen, nothing to select, and the reason. */
    private fun pictureOnly(id: String, info: DeviceInfo, picture: Shot.Got, why: AppError): Capture {
        val warnings = arrayListOf(CaptureWarning(why.code, why.detail))
        if (picture.uniform) warnings += CaptureWarning(ErrorCodes.SECURE_WINDOW, UNIFORM_DETAIL)
        return Capture(
            id = id,
            takenAt = clock.instant(),
            target = Target.Android(info, null),
            screen = ScreenInfo(picture.screenshot.width, picture.screenshot.height),
            screenshot = picture.screenshot,
            roots = emptyList(),
            warnings = warnings,
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

    /** Two ways to get the picture off the phone; the one that worked last time for a device goes first. */
    private val screenStrategies: List<suspend (String) -> Outcome<PngBytes.Found>> = listOf(
        { serial -> screenViaPipe(serial) },
        { serial -> screenViaFile(serial) },
    )

    private suspend fun screenshot(serial: String): Outcome<Png> {
        val first = winningScreen[serial] ?: 0
        val order = listOf(first) + screenStrategies.indices.filter { it != first }
        var firstError: AppError? = null
        for (index in order) {
            when (val r = screenStrategies[index](serial)) {
                is Outcome.Success -> {
                    winningScreen[serial] = index
                    val found = r.value
                    return Outcome.Success(Png(found.bytes, found.width, found.height, withContext(dispatchers.default) { isUniform(found.bytes) }))
                }
                is Outcome.Failure -> {
                    if (firstError == null) firstError = r.error
                    // A missing device or a timeout will not be different the second time: say so now instead of waiting twice.
                    if (r.error.code in DEVICE_LEVEL_FAILURES || r.error.detail?.startsWith(TIMEOUT_PREFIX) == true) return Outcome.Failure(firstError)
                }
            }
        }
        return Outcome.Failure(firstError ?: CodedError(ErrorCodes.SCREENSHOT_FAILED, "adb did not return a PNG image", Remedy.RETRY))
    }

    /** `adb exec-out screencap -p`: the picture comes straight down the pipe. */
    private suspend fun screenViaPipe(serial: String): Outcome<PngBytes.Found> =
        when (val x = run(serial, SHOT_TIMEOUT, ErrorCodes.SCREENSHOT_FAILED, "exec-out", "screencap", "-p")) {
            is Outcome.Failure -> x
            is Outcome.Success -> pngFrom(x.value)
        }

    /** The fallback for phones whose pipe is noisy or broken: save the picture on the device, read the file, delete it. */
    private suspend fun screenViaFile(serial: String): Outcome<PngBytes.Found> {
        val remote = "/data/local/tmp/uiscope-screen.png"
        try {
            when (val d = run(serial, SHOT_TIMEOUT, ErrorCodes.SCREENSHOT_FAILED, "shell", "screencap", "-p", remote)) {
                is Outcome.Failure -> return d
                is Outcome.Success -> AdbErrors.classify(d.value.stderrText() + "\n" + d.value.stdoutText().take(2_000))?.let { return Outcome.Failure(it) }
            }
            return when (val c = run(serial, SHOT_TIMEOUT, ErrorCodes.SCREENSHOT_FAILED, "exec-out", "cat", remote)) {
                is Outcome.Failure -> c
                is Outcome.Success -> pngFrom(c.value)
            }
        } finally {
            withContext(NonCancellable) { run(serial, 5.seconds, ErrorCodes.SCREENSHOT_FAILED, "shell", "rm", "-f", remote) }
        }
    }

    private fun pngFrom(r: ProcessResult): Outcome<PngBytes.Found> {
        PngBytes.find(r.stdout)?.let { return Outcome.Success(it) }
        val text = r.stderrText() + "\n" + String(r.stdout, 0, minOf(r.stdout.size, 2_000), Charsets.UTF_8)
        AdbErrors.classify(text)?.let { return Outcome.Failure(it) }
        return fail(ErrorCodes.SCREENSHOT_FAILED, "adb did not return a PNG image (${r.stdout.size} bytes)")
    }

    private fun writeScreenshot(id: String, bytes: ByteArray): Path? =
        try {
            Files.createDirectories(tmpDir)
            Files.write(tmpDir.resolve("$id.png"), bytes)
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not save the screenshot", e)
            null
        }

    /** True when a grid of sample pixels is one colour (a blank, locked or protected screen). Undecodable images are not called uniform. */
    private fun isUniform(bytes: ByteArray): Boolean {
        val image: BufferedImage = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (e: IOException) {
            logger.log(Level.FINE, "screenshot could not be decoded for the uniform-colour check", e)
            null
        } catch (e: RuntimeException) {
            // Some phones write colour profiles ImageIO rejects; the picture itself is fine, only this optional check is skipped.
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
                ErrorCodes.PROCESS_TIMEOUT -> Outcome.Failure(CodedError(timeoutCode, "$TIMEOUT_PREFIX $timeout", Remedy.RETRY))
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
        const val TIMEOUT_PREFIX = "adb did not answer within"
        const val UNIFORM_DETAIL = "The screenshot is a single colour: the app may block screen capture"
        val SHOT_TIMEOUT = 20.seconds

        /** Dump failures that say nothing about the picture: the screen can still be shown without its elements. */
        val TREE_ONLY_FAILURES = setOf(
            ErrorCodes.DUMP_NULL_ROOT,
            ErrorCodes.DUMP_IDLE_TIMEOUT,
            ErrorCodes.UIAUTOMATOR_BUSY,
            ErrorCodes.DUMP_TIMEOUT,
            ErrorCodes.PARSE_FAILED,
        )

        /** Failures that no second way of taking the picture can fix. */
        val DEVICE_LEVEL_FAILURES = setOf(
            ErrorCodes.ADB_NOT_FOUND,
            ErrorCodes.ADB_START_FAILED,
            ErrorCodes.ADB_VERSION_MISMATCH,
            ErrorCodes.NO_DEVICE,
            ErrorCodes.DEVICE_UNAUTHORIZED,
            ErrorCodes.DEVICE_OFFLINE,
            ErrorCodes.NO_PERMISSIONS,
        )
    }
}
