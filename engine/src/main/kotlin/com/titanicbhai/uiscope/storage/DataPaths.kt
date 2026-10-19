package com.titanicbhai.uiscope.storage

import com.titanicbhai.uiscope.HostOs
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where UIScope keeps its files (decision D2): `%LOCALAPPDATA%\UIScope` on Windows, the platform equivalent elsewhere.
 * Everything below [root] is created on demand by [ensureDirectories].
 */
class DataPaths(val root: Path) {
    val database: Path = root.resolve("db")
    val snapshots: Path = root.resolve("snapshots")
    val logs: Path = root.resolve("logs")
    val platformTools: Path = root.resolve("tools").resolve("platform-tools")
    val tmp: Path = root.resolve("tmp")
    val settingsFile: Path = root.resolve("settings.json")

    fun ensureDirectories(): Outcome<Unit> =
        try {
            listOf(database, snapshots, logs, platformTools, tmp).forEach { Files.createDirectories(it) }
            Outcome.Success(Unit)
        } catch (e: IOException) {
            Outcome.Failure(CodedError(ErrorCodes.DATA_DIR_UNAVAILABLE, "Could not create the data folder at $root (${e.message})"))
        }

    companion object {
        fun resolve(os: HostOs, env: Map<String, String>, home: Path): DataPaths =
            DataPaths(
                when (os) {
                    HostOs.WINDOWS ->
                        (env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                            ?: home.resolve("AppData").resolve("Local"))
                            .resolve("UIScope")
                    HostOs.MACOS -> home.resolve("Library").resolve("Application Support").resolve("UIScope")
                    // XDG: a relative XDG_DATA_HOME must be ignored.
                    HostOs.LINUX, HostOs.OTHER ->
                        (env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }?.takeIf { it.isAbsolute }
                            ?: home.resolve(".local").resolve("share"))
                            .resolve("uiscope")
                },
            )

        /** The v1.0.0-era data folder, read once by the P1-7 migration. */
        fun legacyRoot(home: Path): Path = home.resolve(".uiscope")

        fun forCurrentUser(): DataPaths =
            resolve(HostOs.current(), System.getenv(), Path.of(System.getProperty("user.home")))
    }
}
