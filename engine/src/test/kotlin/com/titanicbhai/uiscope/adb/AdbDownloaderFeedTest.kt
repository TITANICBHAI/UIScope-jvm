package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.HostOs
import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.process.JdkNetworkGateway
import com.titanicbhai.uiscope.process.NetworkConfig
import com.titanicbhai.uiscope.storage.DataPaths
import com.titanicbhai.uiscope.testing.FakeHttpServer
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.blocking
import com.titanicbhai.uiscope.testing.withTempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

/** The download that finds its release in Google's package list (the build pins nothing), end to end against a local server. */
class AdbDownloaderFeedTest {
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

    private fun hex(algorithm: String, bytes: ByteArray) = MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    private fun listing(zipName: String, bytes: ByteArray, sha1: String = hex("SHA-1", bytes)) = """
        <sdk-repository>
          <channel id="c0">stable</channel>
          <remotePackage path="platform-tools">
            <revision><major>35</major><minor>0</minor><micro>2</micro></revision><channelRef ref="c0"/>
            <archives><archive><complete><size>${bytes.size}</size><checksum type="sha1">$sha1</checksum><url>$zipName</url></complete><host-os>linux</host-os></archive></archives>
          </remotePackage>
        </sdk-repository>
    """.trimIndent()

    private fun serve(server: FakeHttpServer, path: String, bytes: ByteArray) = server.route(path) { ex ->
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    private fun downloader(server: FakeHttpServer, dir: Path, pinned: PlatformToolsManifest = PlatformToolsManifest("", emptyMap())): Pair<AdbDownloader, DataPaths> {
        val paths = DataPaths(dir.resolve("data"))
        val gateway = JdkNetworkGateway(NetworkConfig(), dispatchers)
        val feed = PlatformToolsFeed(gateway, server.url("/android/repository/repository2-3.xml"), allowedHost = null, arch = "amd64")
        return AdbDownloader(gateway, paths, pinned, HostOs.LINUX, dispatchers, feed = feed) to paths
    }

    private val goodZip get() = zipOf("platform-tools/adb" to "#!fake adb", "platform-tools/fastboot" to "fake", "platform-tools/NOTICE.txt" to "notice")

    @Test
    fun `with nothing pinned the release is looked up, verified against the list's checksum and installed`() = blocking {
        FakeHttpServer().use { server ->
            withTempDir { dir ->
                val bytes = goodZip
                server.text("/android/repository/repository2-3.xml", listing("platform-tools_r35.0.2-linux.zip", bytes))
                serve(server, "/android/repository/platform-tools_r35.0.2-linux.zip", bytes)
                val (downloader, paths) = downloader(server, dir)
                val consent = downloader.consent().assertSuccess()
                assertTrue(consent.text().contains("package list"), "the dialog explains that the release is looked up first")
                assertTrue(consent.text().contains("dl.google.com"))
                assertTrue(server.requests.isEmpty(), "asking for consent must not touch the network")
                val adb = downloader.install().assertSuccess()
                assertEquals(paths.platformTools.resolve("adb"), adb)
                assertTrue(Files.isRegularFile(adb))
                assertEquals(0L, Files.list(paths.tmp).use { it.count() }, "no partial download or extraction folder is left behind")
            }
        }
    }

    @Test
    fun `a file that does not match the list's checksum is discarded and nothing is installed`() = blocking {
        FakeHttpServer().use { server ->
            withTempDir { dir ->
                val bytes = goodZip
                server.text("/android/repository/repository2-3.xml", listing("pt.zip", bytes, sha1 = "0".repeat(40)))
                serve(server, "/android/repository/pt.zip", bytes)
                val (downloader, paths) = downloader(server, dir)
                downloader.install().assertFailure(ErrorCodes.ADB_DOWNLOAD_CHECKSUM)
                assertFalse(Files.exists(paths.platformTools.resolve("adb")))
                assertEquals(0L, Files.list(paths.tmp).use { it.count() })
            }
        }
    }

    @Test
    fun `a file of the wrong size is discarded even if its checksum were right`() = blocking {
        FakeHttpServer().use { server ->
            withTempDir { dir ->
                val bytes = goodZip
                val xml = listing("pt.zip", bytes).replace("<size>${bytes.size}</size>", "<size>${bytes.size + 5}</size>")
                server.text("/android/repository/repository2-3.xml", xml)
                serve(server, "/android/repository/pt.zip", bytes)
                downloader(server, dir).first.install().assertFailure(ErrorCodes.ADB_DOWNLOAD_CHECKSUM)
            }
        }
    }

    @Test
    fun `a pinned entry wins and the package list is never fetched`() = blocking {
        FakeHttpServer().use { server ->
            withTempDir { dir ->
                val bytes = goodZip
                serve(server, "/pinned.zip", bytes)
                val pinned = PlatformToolsManifest("35.0.0", mapOf(HostOs.LINUX to PlatformToolsEntry(server.url("/pinned.zip"), hex("SHA-256", bytes), bytes.size.toLong())))
                val (downloader, _) = downloader(server, dir, pinned)
                assertTrue(downloader.consent().assertSuccess().text().contains("35.0.0"))
                downloader.install().assertSuccess()
                assertEquals(listOf("/pinned.zip"), server.requests.map { it.uri })
            }
        }
    }

    @Test
    fun `no pin and no feed is still reported as unavailable`() = blocking {
        withTempDir { dir ->
            val gateway = JdkNetworkGateway(NetworkConfig(), dispatchers)
            val bare = AdbDownloader(gateway, DataPaths(dir.resolve("data")), PlatformToolsManifest("", emptyMap()), HostOs.LINUX, dispatchers)
            bare.consent().assertFailure(ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE)
            bare.install().assertFailure(ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE)
        }
    }

    @Test
    fun `when Google's list cannot be fetched the install fails with the network code and leaves nothing behind`() = blocking {
        FakeHttpServer().use { server ->
            withTempDir { dir ->
                server.text("/android/repository/repository2-3.xml", "down", status = 503)
                val (downloader, paths) = downloader(server, dir)
                downloader.install().assertFailure(ErrorCodes.NETWORK_HTTP_STATUS)
                assertFalse(Files.exists(paths.platformTools.resolve("adb")))
            }
        }
    }
}
