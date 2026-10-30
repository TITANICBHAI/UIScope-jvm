package com.titanicbhai.uiscope.storage

import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.settings.ExportFormat
import com.titanicbhai.uiscope.settings.InspectTarget
import com.titanicbhai.uiscope.settings.LogLevelSetting
import com.titanicbhai.uiscope.settings.Renderer
import com.titanicbhai.uiscope.settings.Settings
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.testing.HostileStrings
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.awaitTrue
import com.titanicbhai.uiscope.testing.withTempDir
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Appendix G10 (core half, fixes B12) plus the file store. */
class SettingsStoreTest {
    private val everything = Settings(
        theme = ThemeMode.DARK, checkForUpdates = false, defaultExportFormat = ExportFormat.XML, lastFolder = "C:\\Users\\me\\Doc uments",
        lastTarget = InspectTarget.WINDOWS, adbPath = "/opt/android sdk/platform-tools/adb", lastSerial = "R58M12ABCDE", lastWirelessHost = "192.168.1.20:5555",
        liveIntervalSeconds = 7, pickHotkeyEnabled = false, pickHotkey = "Ctrl+Shift+K", hideWhilePicking = false,
        autoSaveSnapshots = true, retentionCount = 42, retentionBytes = 123_456_789_012L, renderer = Renderer.SOFTWARE, logLevel = LogLevelSetting.DEBUG,
        windowWidth = 1280, windowHeight = 800, windowX = -1920, windowY = 40, windowMaximized = true,
        treePaneWidth = 480, detailsPaneWidth = 360, firstRunDone = true,
    )

    @Test
    fun `every field round trips through the file format`() {
        assertEquals(everything, SettingsJson.decode(SettingsJson.encode(everything)).assertSuccess())
        assertEquals(Settings(), SettingsJson.decode(SettingsJson.encode(Settings())).assertSuccess())
    }

    @Test
    fun `hostile text in the free-text settings round trips and the file is plain ASCII`() {
        for ((name, value) in HostileStrings.all) {
            val s = Settings(adbPath = value.ifEmpty { "x" }, lastFolder = value.ifEmpty { "x" }, lastWirelessHost = value.ifEmpty { "x" })
            val text = SettingsJson.encode(s)
            assertTrue(text.all { it == '\n' || it in ' '..'~' }, name)
            assertEquals(s.sanitized(), SettingsJson.decode(text).assertSuccess(), name)
        }
    }

    @Test
    fun `an empty object gives the defaults and unknown or legacy keys are ignored`() {
        assertEquals(Settings(), SettingsJson.decode("{}").assertSuccess())
        val text = """{"schemaVersion": 99, "theme": "LIGHT", "somethingNew": {"deep": [1,2,{"x":null}]}, "legacy.dark_mode": "true", "liveIntervalSeconds": 4}"""
        val s = SettingsJson.decode(text).assertSuccess()
        assertEquals(ThemeMode.LIGHT, s.theme)
        assertEquals(4, s.liveIntervalSeconds)
        assertEquals(Settings().copy(theme = ThemeMode.LIGHT, liveIntervalSeconds = 4), s)
    }

    @Test
    fun `a value of the wrong type or an unknown name falls back to that setting's default only`() {
        val s = SettingsJson.decode("""{"theme": "PURPLE", "checkForUpdates": "yes", "liveIntervalSeconds": "fast", "retentionCount": 1.5, "windowWidth": "wide", "lastSerial": 12, "renderer": "SOFTWARE"}""").assertSuccess()
        assertEquals(Settings().copy(renderer = Renderer.SOFTWARE), s)
    }

    @Test
    fun `numbers are clamped into their range`() {
        val s = SettingsJson.decode("""{"liveIntervalSeconds": 0, "retentionCount": -5, "retentionBytes": 10, "pickHotkey": "  ", "adbPath": ""}""").assertSuccess()
        assertEquals(Settings.MIN_LIVE_SECONDS, s.liveIntervalSeconds)
        assertEquals(Settings.MIN_RETENTION_COUNT, s.retentionCount)
        assertEquals(Settings.MIN_RETENTION_BYTES, s.retentionBytes)
        assertEquals(Settings.DEFAULT_PICK_HOTKEY, s.pickHotkey)
        assertEquals(null, s.adbPath)
        assertEquals(Settings.MAX_LIVE_SECONDS, SettingsJson.decode("""{"liveIntervalSeconds": 500}""").assertSuccess().liveIntervalSeconds)
    }

    @Test
    fun `text that is not a json object is an error`() {
        SettingsJson.decode("not json").assertFailure(ErrorCodes.PARSE_FAILED)
        SettingsJson.decode("[1,2,3]").assertFailure(ErrorCodes.PARSE_FAILED)
        SettingsJson.decode("").assertFailure(ErrorCodes.PARSE_FAILED)
    }

    // ── the file-backed store ───────────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dispatchers = StandardDispatcherProvider(Dispatchers.Unconfined)

    private fun store(file: java.nio.file.Path, ticks: Channel<Unit>? = null, completed: AtomicInteger = AtomicInteger()) =
        FileSettingsStore(file, scope, dispatchers, debounceMs = 300, delayFn = { _ -> ticks?.receive(); completed.incrementAndGet() })

    @Test
    fun `a missing file gives defaults and creates nothing`() {
        withTempDir { dir ->
            val file = dir.resolve("settings.json")
            assertEquals(Settings(), store(file).settings.value)
            assertFalse(Files.exists(file))
        }
        scope.cancel()
    }

    @Test
    fun `an update is readable at once, before anything is written, and then saved`() {
        withTempDir { dir ->
            val file = dir.resolve("data/settings.json")
            val ticks = Channel<Unit>(Channel.UNLIMITED)
            val s = store(file, ticks)
            s.update { it.copy(theme = ThemeMode.DARK) }
            assertEquals(ThemeMode.DARK, s.settings.value.theme, "the theme applies immediately (B12: no stale reads)")
            assertFalse(Files.exists(file), "nothing is written before the debounce time has passed")
            ticks.trySend(Unit)
            awaitTrue(message = "file written") { Files.exists(file) }
            assertEquals(ThemeMode.DARK, SettingsJson.decode(Files.readString(file)).assertSuccess().theme)
            assertFalse(Files.exists(dir.resolve("data/settings.json.tmp")), "no temporary file is left")
        }
        scope.cancel()
    }

    @Test
    fun `many quick updates are written once and the last value wins`() {
        withTempDir { dir ->
            val file = dir.resolve("settings.json")
            val ticks = Channel<Unit>(Channel.UNLIMITED)
            val completed = AtomicInteger()
            val s = store(file, ticks, completed)
            repeat(20) { n -> s.update { it.copy(liveIntervalSeconds = 1 + n % 10, lastSerial = "S$n") } }
            Thread.sleep(100)
            ticks.trySend(Unit)
            awaitTrue(message = "file written") { Files.exists(file) }
            Thread.sleep(150)
            assertEquals(1, completed.get(), "only the newest write survives the debounce")
            val saved = SettingsJson.decode(Files.readString(file)).assertSuccess()
            assertEquals("S19", saved.lastSerial)
            assertEquals(s.settings.value, saved)
        }
        scope.cancel()
    }

    @Test
    fun `flush writes immediately and a new store reads the same values`() {
        withTempDir { dir ->
            val file = dir.resolve("settings.json")
            val s = store(file, Channel(Channel.UNLIMITED)) // the debounced write never fires in this test
            s.update { everything }
            s.flush()
            assertTrue(Files.exists(file))
            assertEquals(everything, store(file).settings.value, "a restart sees every setting")
        }
        scope.cancel()
    }

    @Test
    fun `reset restores the defaults and saves them`() {
        withTempDir { dir ->
            val file = dir.resolve("settings.json")
            val s = store(file)
            s.update { everything }
            s.reset()
            assertEquals(Settings(), s.settings.value)
            s.flush()
            assertEquals(Settings(), SettingsJson.decode(Files.readString(file)).assertSuccess())
        }
        scope.cancel()
    }

    @Test
    fun `a damaged file is kept as a copy and the defaults are used`() {
        withTempDir { dir ->
            val file = dir.resolve("settings.json")
            Files.writeString(file, "{ this is not json")
            val s = store(file)
            assertEquals(Settings(), s.settings.value)
            assertEquals("{ this is not json", Files.readString(dir.resolve("settings.json.corrupt")))
            s.update { it.copy(theme = ThemeMode.LIGHT) }
            s.flush()
            assertEquals(ThemeMode.LIGHT, SettingsJson.decode(Files.readString(file)).assertSuccess().theme)
            assertEquals("{ this is not json", Files.readString(dir.resolve("settings.json.corrupt")), "the copy is not overwritten")
        }
        scope.cancel()
    }
}
