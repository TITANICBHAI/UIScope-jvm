package com.titanicbhai.uiscope.storage

import com.titanicbhai.uiscope.HostOs
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataPathsTest {
    private val home = Path.of("home-dir", "me")

    @Test
    fun `windows uses LOCALAPPDATA`() {
        val paths = DataPaths.resolve(HostOs.WINDOWS, mapOf("LOCALAPPDATA" to "local-app-data"), home)
        assertEquals(Path.of("local-app-data", "UIScope"), paths.root)
    }

    @Test
    fun `windows falls back to AppData Local under home`() {
        val paths = DataPaths.resolve(HostOs.WINDOWS, emptyMap(), home)
        assertEquals(home.resolve("AppData").resolve("Local").resolve("UIScope"), paths.root)
        assertEquals(paths.root, DataPaths.resolve(HostOs.WINDOWS, mapOf("LOCALAPPDATA" to "  "), home).root)
    }

    @Test
    fun `macos uses Application Support`() {
        val paths = DataPaths.resolve(HostOs.MACOS, emptyMap(), home)
        assertEquals(home.resolve("Library").resolve("Application Support").resolve("UIScope"), paths.root)
    }

    @Test
    fun `linux honours an absolute XDG_DATA_HOME and ignores a relative one`() {
        val xdg = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("xdg-data")
        assertEquals(xdg.resolve("uiscope"), DataPaths.resolve(HostOs.LINUX, mapOf("XDG_DATA_HOME" to xdg.toString()), home).root)
        val fallback = home.resolve(".local").resolve("share").resolve("uiscope")
        assertEquals(fallback, DataPaths.resolve(HostOs.LINUX, mapOf("XDG_DATA_HOME" to "relative/path"), home).root)
        assertEquals(fallback, DataPaths.resolve(HostOs.LINUX, emptyMap(), home).root)
        assertEquals(fallback, DataPaths.resolve(HostOs.OTHER, emptyMap(), home).root)
    }

    @Test
    fun `sub folders and the legacy root`() {
        val paths = DataPaths(Path.of("root"))
        assertEquals(Path.of("root", "db"), paths.database)
        assertEquals(Path.of("root", "snapshots"), paths.snapshots)
        assertEquals(Path.of("root", "logs"), paths.logs)
        assertEquals(Path.of("root", "tools", "platform-tools"), paths.platformTools)
        assertEquals(Path.of("root", "tmp"), paths.tmp)
        assertEquals(Path.of("root", "settings.json"), paths.settingsFile)
        assertEquals(home.resolve(".uiscope"), DataPaths.legacyRoot(home))
    }

    @Test
    fun `ensureDirectories creates everything and can run twice`() {
        withTempDir { dir ->
            val paths = DataPaths(dir.resolve("UIScope"))
            paths.ensureDirectories().assertSuccess()
            paths.ensureDirectories().assertSuccess()
            listOf(paths.database, paths.snapshots, paths.logs, paths.platformTools, paths.tmp).forEach {
                assertTrue(Files.isDirectory(it), "$it should exist")
            }
        }
    }

    @Test
    fun `ensureDirectories reports a coded failure when the root is a file`() {
        withTempDir { dir ->
            val blocker = Files.writeString(dir.resolve("blocker"), "i am a file")
            DataPaths(blocker.resolve("UIScope")).ensureDirectories().assertFailure(ErrorCodes.DATA_DIR_UNAVAILABLE)
        }
    }
}
