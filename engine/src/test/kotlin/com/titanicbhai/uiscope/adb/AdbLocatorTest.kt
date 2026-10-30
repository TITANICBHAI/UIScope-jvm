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
import kotlin.test.assertNull

class AdbLocatorTest {
    private val banner = "Android Debug Bridge version 1.0.41\nVersion 35.0.0-12147458\nInstalled as /x/adb\n"

    private fun fakeAdb(vararg working: Path) = FakeProcessRunner { cmd ->
        if (cmd.size == 2 && cmd[1] == "version" && working.any { it.toString() == cmd[0] }) FakeProcessRunner.ok(banner) else FakeProcessRunner.ok("not adb", exitCode = 1)
    }

    private fun touch(path: Path): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, "binary")
    }

    private class MemoryStore(var value: String? = null) : AdbPathStore {
        override fun load() = value

        override fun save(path: String) {
            value = path
        }
    }

    private fun locator(dir: Path, runner: FakeProcessRunner, store: AdbPathStore = MemoryStore(), env: Map<String, String> = emptyMap(), os: HostOs = HostOs.LINUX) =
        AdbLocator(runner, store, DataPaths(dir.resolve("data")), os, env, dir.resolve("home"), ":")

    @Test
    fun `an adb on PATH is used before the managed copy and reports its version`() = blocking {
        withTempDir { dir ->
            val onPath = touch(dir.resolve("bin/adb"))
            val managed = touch(dir.resolve("data/tools/platform-tools/adb"))
            val l = locator(dir, fakeAdb(onPath, managed), env = mapOf("PATH" to "${dir.resolve("bin")}:${dir.resolve("other")}"))
            val found = l.locate().assertSuccess()
            assertEquals(onPath, found.path)
            assertEquals(AdbSource.PATH, found.source)
            assertEquals("1.0.41 (35.0.0-12147458)", found.version.toString())
        }
    }

    @Test
    fun `order is saved then SDK env then default SDK then PATH then managed`() = blocking {
        withTempDir { dir ->
            val saved = touch(dir.resolve("saved/adb"))
            val sdk = touch(dir.resolve("sdk/platform-tools/adb"))
            val default = touch(dir.resolve("home/Android/Sdk/platform-tools/adb"))
            val onPath = touch(dir.resolve("bin/adb"))
            val managed = touch(dir.resolve("data/tools/platform-tools/adb"))
            val all = fakeAdb(saved, sdk, default, onPath, managed)
            val env = mapOf("ANDROID_SDK_ROOT" to dir.resolve("sdk").toString(), "PATH" to dir.resolve("bin").toString())
            assertEquals(AdbSource.SAVED, locator(dir, all, MemoryStore(saved.toString()), env).locate().assertSuccess().source)
            assertEquals(AdbSource.SDK_ENV, locator(dir, all, MemoryStore(), env).locate().assertSuccess().source)
            assertEquals(AdbSource.SDK_DEFAULT, locator(dir, all, MemoryStore(), env - "ANDROID_SDK_ROOT").locate().assertSuccess().source)
            Files.delete(default)
            assertEquals(AdbSource.PATH, locator(dir, fakeAdb(onPath, managed), MemoryStore(), env - "ANDROID_SDK_ROOT").locate().assertSuccess().source)
            assertEquals(AdbSource.MANAGED, locator(dir, fakeAdb(managed), MemoryStore(), emptyMap()).locate().assertSuccess().source)
        }
    }

    @Test
    fun `a broken candidate is skipped and ANDROID_HOME works`() = blocking {
        withTempDir { dir ->
            val broken = touch(dir.resolve("saved/adb"))
            val home = touch(dir.resolve("androidhome/platform-tools/adb"))
            val found = locator(dir, fakeAdb(home), MemoryStore(broken.toString()), mapOf("ANDROID_HOME" to dir.resolve("androidhome").toString())).locate().assertSuccess()
            assertEquals(home, found.path)
        }
    }

    @Test
    fun `a file that is not adb is rejected even if it runs`() = blocking {
        withTempDir { dir ->
            val fake = touch(dir.resolve("bin/adb"))
            val runner = FakeProcessRunner { FakeProcessRunner.ok("hello world") }
            locator(dir, runner, env = mapOf("PATH" to fake.parent.toString())).locate().assertFailure(ErrorCodes.ADB_NOT_FOUND)
        }
    }

    @Test
    fun `nothing found is ADB_NOT_FOUND`() = blocking {
        withTempDir { dir -> locator(dir, fakeAdb()).locate().assertFailure(ErrorCodes.ADB_NOT_FOUND) }
    }

    @Test
    fun `a chosen path is validated, remembered and found again after a restart`() = blocking {
        withTempDir { dir ->
            val chosen = touch(dir.resolve("mytools/adb"))
            val file = FileAdbPathStore(dir.resolve("data/adb-path.txt"))
            val runner = fakeAdb(chosen)
            assertNull(file.load())
            locator(dir, runner, file).select(dir.resolve("mytools/missing")).assertFailure(ErrorCodes.ADB_NOT_FOUND)
            assertNull(file.load(), "a rejected file is not remembered")
            locator(dir, runner, file).select(chosen).assertSuccess()
            // "restart": a brand new locator and a brand new store object on the same file
            val after = locator(dir, runner, FileAdbPathStore(dir.resolve("data/adb-path.txt"))).locate().assertSuccess()
            assertEquals(chosen, after.path)
            assertEquals(AdbSource.SAVED, after.source)
        }
    }

    @Test
    fun `the chosen path lives in the settings`() = blocking {
        withTempDir { dir ->
            val chosen = touch(dir.resolve("mytools/adb"))
            val settings = com.titanicbhai.uiscope.settings.InMemorySettingsStore()
            val runner = fakeAdb(chosen)
            locator(dir, runner, SettingsAdbPathStore(settings)).select(chosen).assertSuccess()
            assertEquals(chosen.toString(), settings.settings.value.adbPath)
            assertEquals(AdbSource.SAVED, locator(dir, runner, SettingsAdbPathStore(settings)).locate().assertSuccess().source)
        }
    }

    @Test
    fun `windows looks for adb exe under LOCALAPPDATA`() = blocking {
        withTempDir { dir ->
            val sdk = touch(dir.resolve("local/Android/Sdk/platform-tools/adb.exe"))
            val found = locator(dir, fakeAdb(sdk), env = mapOf("LOCALAPPDATA" to dir.resolve("local").toString()), os = HostOs.WINDOWS).locate().assertSuccess()
            assertEquals(AdbSource.SDK_DEFAULT, found.source)
        }
    }
}
