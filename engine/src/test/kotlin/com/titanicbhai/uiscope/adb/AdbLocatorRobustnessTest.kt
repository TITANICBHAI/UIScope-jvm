package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.HostOs
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.storage.DataPaths
import com.titanicbhai.uiscope.testing.FakeProcessRunner
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.blocking
import com.titanicbhai.uiscope.testing.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One odd entry in `PATH` (a quoted folder, a character the file system refuses) used to throw out of [AdbLocator.locate], so the whole
 * Android side sat on "looking for adb" forever. None of these may stop the search.
 */
class AdbLocatorRobustnessTest {
    private val banner = "Android Debug Bridge version 1.0.41\nVersion 35.0.0-12147458\nInstalled as /x/adb\n"
    private val host = HostOs.current()
    private val exe = if (host == HostOs.WINDOWS) "adb.exe" else "adb"
    private val sep = if (host == HostOs.WINDOWS) ";" else ":"

    private class MemoryStore(var value: String? = null) : AdbPathStore {
        override fun load() = value

        override fun save(path: String) {
            value = path
        }
    }

    private fun fakeAdb(vararg working: Path) = FakeProcessRunner { cmd ->
        if (cmd.size == 2 && cmd[1] == "version" && working.any { it.toString() == cmd[0] }) FakeProcessRunner.ok(banner) else FakeProcessRunner.ok("not adb", exitCode = 1)
    }

    private fun touch(path: Path): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, "binary")
    }

    private fun locator(dir: Path, runner: FakeProcessRunner, store: AdbPathStore = MemoryStore(), env: Map<String, String> = emptyMap()) =
        AdbLocator(runner, store, DataPaths(dir.resolve("data")), host, env, dir.resolve("home"), sep)

    @Test
    fun `a quoted PATH entry is unquoted and used`() = blocking {
        withTempDir { dir ->
            val adb = touch(dir.resolve("quoted tools/$exe"))
            val found = locator(dir, fakeAdb(adb), env = mapOf("PATH" to "\"${adb.parent}\"")).locate().assertSuccess()
            assertEquals(adb, found.path)
            assertEquals(AdbSource.PATH, found.source)
        }
    }

    @Test
    fun `an entry the file system rejects is skipped and the next one is still found`() = blocking {
        withTempDir { dir ->
            val adb = touch(dir.resolve("good/$exe"))
            // A NUL character is rejected by Path.of on every platform.
            val found = locator(dir, fakeAdb(adb), env = mapOf("PATH" to "bad\u0000folder$sep${adb.parent}")).locate().assertSuccess()
            assertEquals(adb, found.path)
        }
    }

    @Test
    fun `a saved path or an SDK variable that is not a valid path does not break the search`() = blocking {
        withTempDir { dir ->
            val adb = touch(dir.resolve("bin/$exe"))
            val env = mapOf("ANDROID_HOME" to "bad\u0000sdk", "ANDROID_SDK_ROOT" to "   ", "PATH" to adb.parent.toString())
            assertEquals(adb, locator(dir, fakeAdb(adb), MemoryStore("saved\u0000path"), env).locate().assertSuccess().path)
        }
    }

    @Test
    fun `empty and blank PATH entries are ignored`() = blocking {
        withTempDir { dir ->
            locator(dir, fakeAdb(), env = mapOf("PATH" to "$sep $sep\t$sep")).locate().assertFailure(ErrorCodes.ADB_NOT_FOUND)
        }
    }

    @Test
    fun `the old standalone SDK folder under Program Files is checked on Windows`() = blocking {
        withTempDir { dir ->
            val adb = touch(dir.resolve("pf86/Android/android-sdk/platform-tools/adb.exe"))
            val l = AdbLocator(fakeAdb(adb), MemoryStore(), DataPaths(dir.resolve("data")), HostOs.WINDOWS, mapOf("ProgramFiles(x86)" to dir.resolve("pf86").toString()), dir.resolve("home"), ";")
            assertEquals(adb, l.locate().assertSuccess().path)
        }
    }
}
