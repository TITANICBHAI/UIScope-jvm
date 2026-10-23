package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.HostOs
import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.process.JdkNetworkGateway
import com.titanicbhai.uiscope.process.NetworkConfig
import com.titanicbhai.uiscope.storage.DataPaths
import com.titanicbhai.uiscope.testing.FakeHttpServer
import com.titanicbhai.uiscope.testing.FakeProcessRunner
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.awaitTrue
import com.titanicbhai.uiscope.testing.blocking
import com.titanicbhai.uiscope.testing.withTempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Appendix G9-zip (fixes M4) and the rest of P2-4. Everything runs against a local fake server. */
class AdbDownloaderTest {
    private val dispatchers = StandardDispatcherProvider(Dispatchers.Unconfined)

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun serve(server: FakeHttpServer, path: String, bytes: ByteArray) = server.route(path) { ex ->
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    private fun downloader(server: FakeHttpServer, dir: Path, bytes: ByteArray, sha256: String = sha(bytes), os: HostOs = HostOs.LINUX, limits: ZipSafe.Limits = ZipSafe.Limits()): Pair<AdbDownloader, DataPaths> {
        val paths = DataPaths(dir.resolve("data"))
        val manifest = PlatformToolsManifest("35.0.2", mapOf(os to PlatformToolsEntry(server.url("/pt.zip"), sha256, bytes.size.toLong())))
        return AdbDownloader(JdkNetworkGateway(NetworkConfig(), dispatchers), paths, manifest, os, dispatchers, limits) to paths
    }

    private val goodZip get() = zipOf("platform-tools/adb" to "#!fake adb", "platform-tools/fastboot" to "fake", "platform-tools/NOTICE.txt" to "notice", "platform-tools/lib64/libc++.so" to "lib")

    private fun tmpIsClean(paths: DataPaths) = Files.list(paths.tmp).use { it.count() } == 0L

    @Test
    fun `a good archive is downloaded, verified, installed and found as the managed adb`() = blocking {
        FakeHttpServer().use { server ->
            withTempDir { dir ->
                val bytes = goodZip
                serve(server, "/pt.zip", bytes)
                val (downloader, paths) = downloader(server, dir, bytes)
                val progress = AtomicLong(-1)
                val adb = downloader.install { read, _ -> progress.set(read) }.assertSuccess()
                assertEquals(paths.platformTools.resolve("adb"), adb)
                assertEquals("#!fake adb", Files.readString(adb))
                assertTrue(Files.isRegularFile(paths.platformTools.resolve("lib64/libc++.so")))
                assertTrue(Files.isExecutable(adb), "adb must be executable on Linux and macOS")
                assertEquals(bytes.size.toLong(), progress.get())
                assertTrue(tmpIsClean(paths), "no temporary files may remain")
                // the locator finds it after "restart" without any saved setting
                val runner = FakeProcessRunner { FakeProcessRunner.ok("Android Debug Bridge version 1.0.41\nVersion 35.0.2-1\n") }
                val locator = AdbLocator(runner, FileAdbPathStore(dir.resolve("none.txt")), paths, HostOs.LINUX, emptyMap(), dir.resolve("home"), ":")
                assertEquals(AdbSource.MANAGED, locator.locate().assertSuccess().source)
            }
        }
    }

    @Test
    fun `a wrong checksum is rejected and nothing is installed or left behind`() = blocking {
        FakeHttpServer().use { server ->
            withTempDir { dir ->
                val bytes = goodZip
                serve(server, "/pt.zip", bytes)
                val (downloader, paths) = downloader(server, dir, bytes, sha256 = sha("something else".toByteArray()))
                downloader.install().assertFailure(ErrorCodes.ADB_DOWNLOAD_CHECKSUM)
                assertFalse(Files.exists(paths.platformTools.resolve("adb")), "nothing may be installed")
                assertTrue(tmpIsClean(paths))
            }
        }
    }

    @Test
    fun `zip slip entries are all refused and nothing is written outside`() = blocking {
        val evilNames = listOf("../evil.txt", "platform-tools/../../evil.txt", "/abs/evil.txt", "C:\\evil.txt", "C:/evil.txt", "..\\evil.txt", "platform-tools\\..\\..\\evil.txt", "\\\\server\\share\\evil.txt")
        for (name in evilNames) {
            FakeHttpServer().use { server ->
                withTempDir { dir ->
                    val bytes = zipOf("platform-tools/adb" to "adb", name to "pwned")
                    serve(server, "/pt.zip", bytes)
                    val (downloader, paths) = downloader(server, dir, bytes)
                    downloader.install().assertFailure(ErrorCodes.ADB_DOWNLOAD_INVALID_ARCHIVE)
                    assertFalse(Files.exists(paths.platformTools.resolve("adb")), "$name: nothing may be installed")
                    val strays = Files.walk(dir).use { walk -> walk.filter { it.fileName.toString() == "evil.txt" }.count() }
                    assertEquals(0L, strays, "$name: evil.txt must not be created anywhere under the test folder")
                    assertFalse(Files.exists(dir.parent.resolve("evil.txt")), "$name: nothing above the data folder")
                    assertTrue(tmpIsClean(paths), name)
                }
            }
        }
    }

    @Test
    fun `entry name rules`() {
        listOf("platform-tools/adb", "platform-tools/lib64/x.so", "a/b/c.txt", "platform-tools/").forEach { assertTrue(ZipSafe.isSafeEntryName(it), it) }
        listOf("", "..", "../x", "a/../../x", "/x", "\\x", "C:x", "c:\\x", "a\\..\\x", "a\u0000b").forEach { assertFalse(ZipSafe.isSafeEntryName(it), "'$it'") }
        assertTrue(ZipSafe.isSafeEntryName("platform-tools/..hidden"), "a name that merely starts with dots is fine")
    }

    @Test
    fun `an archive without adb or beyond the limits is refused`() = blocking {
        FakeHttpServer().use { server ->
            withTempDir { dir ->
                val noAdb = zipOf("platform-tools/fastboot" to "x")
                serve(server, "/pt.zip", noAdb)
                downloader(server, dir, noAdb).first.install().assertFailure(ErrorCodes.ADB_DOWNLOAD_INVALID_ARCHIVE)
            }
        }
        FakeHttpServer().use { server ->
            withTempDir { dir ->
                val many = zipOf(*(1..10).map { "platform-tools/f$it" to "x" }.toTypedArray(), "platform-tools/adb" to "x")
                serve(server, "/pt.zip", many)
                downloader(server, dir, many, limits = ZipSafe.Limits(maxEntries = 5)).first.install().assertFailure(ErrorCodes.ADB_DOWNLOAD_INVALID_ARCHIVE)
            }
        }
        FakeHttpServer().use { server ->
            withTempDir { dir ->
                val bomb = zipOf("platform-tools/adb" to "x", "platform-tools/big.bin" to "0".repeat(200_000))
                serve(server, "/pt.zip", bomb)
                val (d, paths) = downloader(server, dir, bomb, limits = ZipSafe.Limits(maxTotalBytes = 100_000))
                d.install().assertFailure(ErrorCodes.ADB_DOWNLOAD_INVALID_ARCHIVE)
                assertTrue(tmpIsClean(paths))
            }
        }
    }

    @Test
    fun `a file that is not a zip is refused`() = blocking {
        FakeHttpServer().use { server ->
            withTempDir { dir ->
                val junk = "this is not a zip file".toByteArray()
                serve(server, "/pt.zip", junk)
                downloader(server, dir, junk).first.install().assertFailure(ErrorCodes.ADB_DOWNLOAD_INVALID_ARCHIVE)
            }
        }
    }

    @Test
    fun `an existing install is replaced as a whole`() = blocking {
        FakeHttpServer().use { server ->
            withTempDir { dir ->
                val bytes = goodZip
                serve(server, "/pt.zip", bytes)
                val (downloader, paths) = downloader(server, dir, bytes)
                Files.createDirectories(paths.platformTools)
                Files.writeString(paths.platformTools.resolve("adb"), "OLD")
                Files.writeString(paths.platformTools.resolve("stale-file"), "stale")
                downloader.install().assertSuccess()
                assertEquals("#!fake adb", Files.readString(paths.platformTools.resolve("adb")))
                assertFalse(Files.exists(paths.platformTools.resolve("stale-file")), "the old folder is replaced, not merged")
                val leftovers = Files.list(paths.platformTools.parent).use { s -> s.map { it.fileName.toString() }.toList() }
                assertEquals(listOf("platform-tools"), leftovers, "no backup folder is left behind")
            }
        }
    }

    @Test
    fun `cancelling during the download leaves no partial file and no install`() = blocking {
        FakeHttpServer().use { server ->
            withTempDir { dir ->
                server.stall("/pt.zip", firstBytes = 2_000, stallMillis = 30_000)
                val (downloader, paths) = downloader(server, dir, ByteArray(10))
                val progressed = AtomicLong(0)
                val job = launch(Dispatchers.Default) { downloader.install { read, _ -> progressed.set(read) } }
                awaitTrue(message = "first bytes arrive") { progressed.get() > 0 }
                job.cancel()
                withTimeout(15_000) { job.join() }
                assertFalse(Files.exists(paths.platformTools.resolve("adb")), "nothing may be installed")
                assertTrue(tmpIsClean(paths))
            }
        }
    }

    @Test
    fun `without a pinned manifest nothing is downloaded and the consent explains the download otherwise`() = blocking {
        FakeHttpServer().use { server ->
            withTempDir { dir ->
                val empty = AdbDownloader(JdkNetworkGateway(NetworkConfig(), dispatchers), DataPaths(dir.resolve("data")), PlatformToolsManifestData.MANIFEST, HostOs.LINUX, dispatchers)
                empty.consent().assertFailure(ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE)
                empty.install().assertFailure(ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE)
                assertEquals(0, server.requests.size)
                val bytes = goodZip
                val (downloader, paths) = downloader(server, dir, bytes)
                val consent = downloader.consent().assertSuccess()
                val text = consent.text()
                assertTrue(text.contains(server.url("/pt.zip")) && text.contains("35.0.2") && text.contains(paths.platformTools.toString()) && text.contains("MB"), text)
                val otherOs = downloader(server, dir, bytes, os = HostOs.WINDOWS).first
                assertEquals(HostOs.WINDOWS, HostOs.WINDOWS)
                otherOs.consent().assertSuccess()
                AdbDownloader(JdkNetworkGateway(NetworkConfig(), dispatchers), paths, PlatformToolsManifest("1", emptyMap()), HostOs.MACOS, dispatchers).consent().assertFailure(ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE)
            }
        }
    }
}
