package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.HostOs
import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.process.JdkNetworkGateway
import com.titanicbhai.uiscope.process.NetworkConfig
import com.titanicbhai.uiscope.testing.FakeHttpServer
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.blocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

/** The in-app adb download used to be permanently unavailable (empty pinned manifest). These tests pin the replacement: Google's package list. */
class PlatformToolsFeedTest {
    private val dispatchers = StandardDispatcherProvider(Dispatchers.Unconfined)
    private val sha1Win = "1".repeat(40)
    private val sha1Linux = "2".repeat(40)
    private val sha1Mac = "3".repeat(40)

    /** Shaped like the real `repository2-3.xml`: a namespaced root, unrelated packages, a stable and a beta platform-tools. */
    private fun listing(
        stableWinUrl: String = "platform-tools_r35.0.2-win.zip",
        winChecksum: String = """<checksum type="sha1">$sha1Win</checksum>""",
    ) = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/03" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:generic="http://schemas.android.com/repository/android/generic/02">
          <license id="android-sdk-license" type="text">terms</license>
          <channel id="channel-0">stable</channel>
          <channel id="channel-1">beta</channel>
          <remotePackage path="build-tools;34.0.0">
            <revision><major>34</major><minor>0</minor><micro>0</micro></revision>
            <channelRef ref="channel-0"/>
            <archives><archive><complete><size>1</size><checksum type="sha1">${"9".repeat(40)}</checksum><url>build-tools_r34-windows.zip</url></complete><host-os>windows</host-os></archive></archives>
          </remotePackage>
          <remotePackage path="platform-tools">
            <type-details xsi:type="generic:genericDetailsType"/>
            <revision><major>35</major><minor>0</minor><micro>2</micro></revision>
            <display-name>Android SDK Platform-Tools</display-name>
            <uses-license ref="android-sdk-license"/>
            <channelRef ref="channel-0"/>
            <archives>
              <archive><complete><size>7000001</size><checksum type="sha1">$sha1Mac</checksum><url>platform-tools_r35.0.2-darwin.zip</url></complete><host-os>macosx</host-os></archive>
              <archive><complete><size>7000002</size><checksum type="sha1">$sha1Linux</checksum><url>platform-tools_r35.0.2-linux.zip</url></complete><host-os>linux</host-os></archive>
              <archive><complete><size>7000003</size>$winChecksum<url>$stableWinUrl</url></complete><host-os>windows</host-os></archive>
            </archives>
          </remotePackage>
          <remotePackage path="platform-tools">
            <revision><major>36</major><minor>0</minor><micro>0</micro><preview>1</preview></revision>
            <channelRef ref="channel-1"/>
            <archives><archive><complete><size>8000000</size><checksum type="sha1">${"7".repeat(40)}</checksum><url>platform-tools_r36.0.0-rc01-win.zip</url></complete><host-os>windows</host-os></archive></archives>
          </remotePackage>
        </sdk:sdk-repository>
    """.trimIndent()

    private fun feed(gateway: JdkNetworkGateway = JdkNetworkGateway(NetworkConfig(), dispatchers), allowedHost: String? = PlatformToolsFeed.GOOGLE_HOST, arch: String = "amd64") =
        PlatformToolsFeed(gateway, allowedHost = allowedHost, arch = arch)

    @Test
    fun `the stable release is chosen for each operating system with its size and checksum`() {
        val win = feed().parse(listing(), HostOs.WINDOWS).assertSuccess()
        assertEquals("35.0.2", win.revision)
        val entry = win.entries.getValue(HostOs.WINDOWS)
        assertEquals("https://dl.google.com/android/repository/platform-tools_r35.0.2-win.zip", entry.url)
        assertEquals(7_000_003L, entry.sizeBytes)
        assertEquals(sha1Win, entry.sha1)
        assertEquals("", entry.sha256, "the list publishes SHA-1 only; the pinned SHA-256 stays empty")
        assertEquals(sha1Linux, feed().parse(listing(), HostOs.LINUX).assertSuccess().entries.getValue(HostOs.LINUX).sha1)
        assertEquals(sha1Mac, feed().parse(listing(), HostOs.MACOS).assertSuccess().entries.getValue(HostOs.MACOS).sha1)
    }

    @Test
    fun `a beta with a higher number never beats the stable release`() {
        val manifest = feed().parse(listing(), HostOs.WINDOWS).assertSuccess()
        assertEquals("35.0.2", manifest.revision)
        assertTrue(manifest.entries.getValue(HostOs.WINDOWS).url.contains("r35.0.2"))
    }

    @Test
    fun `a sha256 checksum is used when the list offers one`() {
        val sha256 = "a".repeat(64)
        val entry = feed().parse(listing(winChecksum = """<checksum type="sha-256">$sha256</checksum>"""), HostOs.WINDOWS).assertSuccess().entries.getValue(HostOs.WINDOWS)
        assertEquals(sha256, entry.sha256)
        assertNull(entry.sha1)
    }

    @Test
    fun `an archive address off dl google com is refused`() {
        feed().parse(listing(stableWinUrl = "https://evil.example.com/platform-tools.zip"), HostOs.WINDOWS).assertFailure(ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE)
        feed().parse(listing(stableWinUrl = "http://dl.google.com/android/repository/platform-tools.zip"), HostOs.WINDOWS).assertFailure(ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE)
    }

    @Test
    fun `an archive without a usable checksum is never offered`() {
        feed().parse(listing(winChecksum = ""), HostOs.WINDOWS).assertFailure(ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE)
        feed().parse(listing(winChecksum = """<checksum type="sha1">not-hex</checksum>"""), HostOs.WINDOWS).assertFailure(ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE)
    }

    @Test
    fun `text that is not the package list fails cleanly and a doctype is refused`() {
        feed().parse("<html>nope</html>", HostOs.WINDOWS).assertFailure(ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE)
        feed().parse("not xml at all", HostOs.WINDOWS).assertFailure(ErrorCodes.PARSE_FAILED)
        feed().parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><sdk-repository>&e;</sdk-repository>", HostOs.WINDOWS).assertFailure(ErrorCodes.PARSE_FAILED)
        feed().parse(listing(), HostOs.OTHER).assertFailure(ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE)
    }

    @Test
    fun `an arm machine takes the arm archive and an intel machine the other one`() {
        val xml = """
            <sdk-repository>
              <channel id="c0">stable</channel>
              <remotePackage path="platform-tools">
                <revision><major>35</major><minor>0</minor><micro>2</micro></revision><channelRef ref="c0"/>
                <archives>
                  <archive><complete><size>1</size><checksum type="sha1">${"a".repeat(40)}</checksum><url>x-arm.zip</url></complete><host-os>macosx</host-os><host-arch>aarch64</host-arch></archive>
                  <archive><complete><size>1</size><checksum type="sha1">${"b".repeat(40)}</checksum><url>x-intel.zip</url></complete><host-os>macosx</host-os></archive>
                </archives>
              </remotePackage>
            </sdk-repository>
        """.trimIndent()
        assertTrue(feed(arch = "aarch64").parse(xml, HostOs.MACOS).assertSuccess().entries.getValue(HostOs.MACOS).url.endsWith("x-arm.zip"))
        assertTrue(feed(arch = "x86_64").parse(xml, HostOs.MACOS).assertSuccess().entries.getValue(HostOs.MACOS).url.endsWith("x-intel.zip"))
    }

    @Test
    fun `resolve downloads the list through the gateway and resolves relative archive addresses against it`() = blocking {
        FakeHttpServer().use { server ->
            server.text("/android/repository/repository2-3.xml", listing())
            val manifest = PlatformToolsFeed(JdkNetworkGateway(NetworkConfig(), dispatchers), server.url("/android/repository/repository2-3.xml"), allowedHost = null)
                .resolve(HostOs.WINDOWS).assertSuccess()
            assertEquals(server.url("/android/repository/platform-tools_r35.0.2-win.zip"), manifest.entries.getValue(HostOs.WINDOWS).url)
        }
    }

    @Test
    fun `a server error while fetching the list is reported, not swallowed`() = blocking {
        FakeHttpServer().use { server ->
            server.text("/list.xml", "boom", status = 500)
            PlatformToolsFeed(JdkNetworkGateway(NetworkConfig(), dispatchers), server.url("/list.xml"), allowedHost = null).resolve(HostOs.WINDOWS)
                .assertFailure(ErrorCodes.NETWORK_HTTP_STATUS)
        }
    }
}
