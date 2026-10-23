package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.testing.FakeProcessRunner
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.blocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** G9-wifi: pair/connect/mdns outputs. Failures must show adb's own message, unmodified. */
class AdbWifiTest {
    @Test
    fun `pair success gives the address`() {
        val r = assertIs<WirelessResult.Success>(WirelessParsers.parsePair("Successfully paired to 192.168.1.20:37099 [guid=adb-R58M12ABCDE-aBcDeF]\n"))
        assertEquals("192.168.1.20:37099", r.address)
    }

    @Test
    fun `every pair failure carries adb's own message and hints`() {
        for (message in listOf("Failed: Unable to start pairing client.", "error: protocol fault (couldn't read status): Connection reset by peer", "Failed to authenticate to 192.168.1.20:37099")) {
            val r = assertIs<WirelessResult.Failure>(WirelessParsers.parsePair("$message\n"))
            assertEquals(ErrorCodes.WIRELESS_PAIR_FAILED, r.code)
            assertEquals(message, r.adbMessage)
            assertTrue(r.hints.size >= 3)
        }
        assertEquals("adb gave no message", (WirelessParsers.parsePair("") as WirelessResult.Failure).adbMessage)
    }

    @Test
    fun `connect success and already connected`() {
        val ok = assertIs<WirelessResult.Success>(WirelessParsers.parseConnect("connected to 192.168.1.20:5555\n"))
        assertEquals("192.168.1.20:5555", ok.address)
        assertEquals(false, ok.alreadyConnected)
        val again = assertIs<WirelessResult.Success>(WirelessParsers.parseConnect("already connected to 192.168.1.20:5555\n"))
        assertEquals(true, again.alreadyConnected)
        assertEquals("already connected to 192.168.1.20:5555", again.message)
    }

    @Test
    fun `every connect failure carries adb's own message`() {
        val messages = listOf(
            "failed to connect to '192.168.1.20:5555': Connection refused",
            "cannot connect to 192.168.1.20:5555: No connection could be made because the target machine actively refused it. (10061)",
            "failed to authenticate to 192.168.1.20:5555",
            "failed to connect to 192.168.1.20:5555",
        )
        for (message in messages) {
            val r = assertIs<WirelessResult.Failure>(WirelessParsers.parseConnect("$message\n"))
            assertEquals(ErrorCodes.WIRELESS_CONNECT_FAILED, r.code)
            assertEquals(message, r.adbMessage)
        }
    }

    @Test
    fun `mdns services are split into pairing and connect entries`() {
        val out = "List of discovered mdns services\nadb-R58M12ABCDE-aBcDeF\t_adb-tls-connect._tcp.\t192.168.1.20:41233\nadb-R58M12ABCDE-xYz\t_adb-tls-pairing._tcp.\t192.168.1.20:37099\nsomething\t_other._tcp.\t1.2.3.4:5\n"
        assertEquals(
            listOf(MdnsService("adb-R58M12ABCDE-aBcDeF", MdnsService.Kind.CONNECT, "192.168.1.20:41233"), MdnsService("adb-R58M12ABCDE-xYz", MdnsService.Kind.PAIRING, "192.168.1.20:37099")),
            WirelessParsers.parseMdns(out),
        )
        assertEquals(emptyList(), WirelessParsers.parseMdns("List of discovered mdns services\n"))
    }

    @Test
    fun `commands use the right arguments and timeouts`() = blocking {
        val runner = FakeProcessRunner { cmd -> if (cmd[1] == "pair") FakeProcessRunner.ok("Successfully paired to 192.168.1.20:37099 [guid=x]") else FakeProcessRunner.ok("connected to 192.168.1.20:5555") }
        val wifi = AdbWifi("adb", runner)
        assertIs<WirelessResult.Success>(wifi.pair("192.168.1.20:37099", "123456").assertSuccess())
        assertIs<WirelessResult.Success>(wifi.connect("192.168.1.20:5555").assertSuccess())
        wifi.disconnect("192.168.1.20:5555").assertSuccess()
        wifi.mdnsServices().assertSuccess()
        assertEquals(
            listOf(listOf("adb", "pair", "192.168.1.20:37099", "123456"), listOf("adb", "connect", "192.168.1.20:5555"), listOf("adb", "disconnect", "192.168.1.20:5555"), listOf("adb", "mdns", "services")),
            runner.calls.toList(),
        )
        assertEquals(listOf(15.seconds, 15.seconds, 5.seconds, 5.seconds), runner.timeouts.toList())
    }

    @Test
    fun `bad input never reaches adb`() = blocking {
        val runner = FakeProcessRunner { FakeProcessRunner.ok("") }
        val wifi = AdbWifi("adb", runner)
        for (address in listOf("", "192.168.1.20", "192.168.1.20:0", "192.168.1.20:70000", "host name:5555", "-s evil:5555", "192.168.1.20:5555 extra")) {
            assertEquals(ErrorCodes.WIRELESS_CONNECT_FAILED, (wifi.connect(address).assertSuccess() as WirelessResult.Failure).code, address)
        }
        assertEquals(ErrorCodes.WIRELESS_PAIR_FAILED, (wifi.pair("192.168.1.20:37099", "12345").assertSuccess() as WirelessResult.Failure).code)
        assertEquals(ErrorCodes.WIRELESS_PAIR_FAILED, (wifi.pair("192.168.1.20:37099", "abcdef").assertSuccess() as WirelessResult.Failure).code)
        assertEquals(0, runner.calls.size)
    }

    @Test
    fun `a missing adb is ADB_NOT_FOUND`() = blocking {
        AdbWifi("adb", FakeProcessRunner { null }).connect("192.168.1.20:5555").assertFailure(ErrorCodes.ADB_NOT_FOUND)
    }
}
