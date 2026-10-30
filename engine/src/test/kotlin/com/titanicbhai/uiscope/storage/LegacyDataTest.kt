package com.titanicbhai.uiscope.storage

import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyDataTest {
    private fun setup(dir: Path): Triple<Path, DataPaths, LegacyData> {
        val legacy = dir.resolve("home").resolve(".uiscope")
        val paths = DataPaths(dir.resolve("data"))
        paths.ensureDirectories().assertSuccess()
        return Triple(legacy, paths, LegacyData(legacy, paths))
    }

    private fun fakeTools(root: Path) {
        Files.createDirectories(root.resolve("platform-tools").resolve("lib64"))
        Files.write(root.resolve("platform-tools").resolve("adb"), byteArrayOf(1, 2, 3))
        Files.write(root.resolve("platform-tools").resolve("lib64").resolve("libc++.so"), byteArrayOf(4))
    }

    @Test
    fun `platform-tools move once into the empty new tools folder`() {
        withTempDir { dir ->
            val (legacy, paths, data) = setup(dir)
            fakeTools(legacy)
            assertTrue(data.movePlatformTools().assertSuccess())
            assertTrue(Files.isRegularFile(paths.platformTools.resolve("adb")))
            assertTrue(Files.isRegularFile(paths.platformTools.resolve("lib64").resolve("libc++.so")))
            assertFalse(Files.exists(legacy.resolve("platform-tools")))
            assertFalse(data.movePlatformTools().assertSuccess(), "a second call must do nothing")
        }
    }

    @Test
    fun `nothing is moved when the new folder already has adb or the old one has none`() {
        withTempDir { dir ->
            val (legacy, paths, data) = setup(dir)
            fakeTools(legacy)
            Files.write(paths.platformTools.resolve("adb.exe"), byteArrayOf(9))
            assertFalse(data.movePlatformTools().assertSuccess())
            assertTrue(Files.isRegularFile(legacy.resolve("platform-tools").resolve("adb")))
            Files.delete(paths.platformTools.resolve("adb.exe"))
            Files.delete(legacy.resolve("platform-tools").resolve("adb"))
            assertFalse(data.movePlatformTools().assertSuccess(), "a folder without adb is not worth moving")
        }
    }

    @Test
    fun `leftovers are measured and deleted only on request`() {
        withTempDir { dir ->
            val (legacy, _, data) = setup(dir)
            assertEquals(0L, data.leftoverBytes())
            Files.createDirectories(legacy.resolve("images"))
            Files.write(legacy.resolve("sessions.db"), ByteArray(1000))
            Files.write(legacy.resolve("images").resolve("a.png"), ByteArray(500))
            assertEquals(1500L, data.leftoverBytes())
            assertTrue(Files.exists(legacy))
            data.deleteLeftovers().assertSuccess()
            assertFalse(Files.exists(legacy))
            data.deleteLeftovers().assertSuccess()
        }
    }
}
