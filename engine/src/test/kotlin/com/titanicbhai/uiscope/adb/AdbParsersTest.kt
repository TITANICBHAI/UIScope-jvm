package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.model.Connection
import com.titanicbhai.uiscope.model.DeviceState
import com.titanicbhai.uiscope.testing.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Appendix G9 (fixes M3): the device list parser, using the exact output from the plan. */
class AdbParsersTest {
    private val listing = AdbParsers.parseDevices(Fixtures.text("adb/devices-g9.txt"))
    private fun device(serial: String) = listing.devices.single { it.serial == serial }

    @Test
    fun `the G9 output gives exactly six devices`() {
        assertEquals(
            listOf("R58M12ABCDE", "emulator-5554", "0123456789ABCDEF", "adb-R58M12ABCDE-aBcDeF._adb-tls-connect._tcp", "ZY22XXXXXXXX", "192.168.1.20:5555"),
            listing.devices.map { it.serial },
        )
    }

    @Test
    fun `states are read from the state column`() {
        assertEquals(DeviceState.DEVICE, device("R58M12ABCDE").state)
        assertEquals(DeviceState.DEVICE, device("emulator-5554").state)
        assertEquals(DeviceState.NO_PERMISSIONS, device("0123456789ABCDEF").state)
        assertEquals(DeviceState.UNAUTHORIZED, device("ZY22XXXXXXXX").state)
        assertEquals(DeviceState.OFFLINE, device("192.168.1.20:5555").state)
        assertTrue(device("R58M12ABCDE").isUsable)
        assertFalse(device("ZY22XXXXXXXX").isUsable)
    }

    @Test
    fun `model product and transport id come from the key value pairs`() {
        val phone = device("R58M12ABCDE")
        assertEquals("SM_G991B", phone.model)
        assertEquals("o1sxxx", phone.product)
        assertEquals("3", phone.transportId)
        assertNull(device("ZY22XXXXXXXX").model)
        assertEquals("sdk_gphone64_x86_64", device("emulator-5554").model)
    }

    @Test
    fun `the connection kind is derived from the serial and the usb field`() {
        assertEquals(Connection.USB, device("R58M12ABCDE").connection)
        assertEquals(Connection.EMULATOR, device("emulator-5554").connection)
        assertEquals(Connection.WIFI, device("adb-R58M12ABCDE-aBcDeF._adb-tls-connect._tcp").connection)
        assertEquals(Connection.WIFI, device("192.168.1.20:5555").connection)
        assertEquals(Connection.USB, device("ZY22XXXXXXXX").connection)
        assertEquals(Connection.UNKNOWN, device("0123456789ABCDEF").connection)
    }

    @Test
    fun `daemon chatter and the version warning are not devices`() {
        assertTrue(listing.devices.none { it.serial.startsWith("*") || it.serial == "adb" && it.state != DeviceState.DEVICE || it.serial == "List" })
        assertEquals(listOf<AdbSignal>(AdbSignal.VersionMismatch(41, 40)), listing.signals)
    }

    @Test
    fun `an empty listing and noise only give no devices`() {
        assertEquals(0, AdbParsers.parseDevices("List of devices attached\n\n").devices.size)
        assertEquals(0, AdbParsers.parseDevices("* daemon not running; starting now at tcp:5037\n* daemon started successfully\n").devices.size)
        assertEquals(0, AdbParsers.parseDevices("").devices.size)
        assertEquals(0, AdbParsers.parseDevices("error: cannot connect to daemon").devices.size)
    }

    @Test
    fun `windows line endings and tabs are accepted`() {
        val out = "List of devices attached\r\nABC123\tdevice product:p model:M device:d transport_id:1\r\n\r\n"
        val d = AdbParsers.parseDevices(out).devices.single()
        assertEquals("ABC123", d.serial)
        assertEquals("M", d.model)
    }

    @Test
    fun `version banner`() {
        val v = AdbParsers.parseVersion("Android Debug Bridge version 1.0.41\nVersion 35.0.0-12147458\nInstalled as C:\\tools\\adb.exe\nRunning on Windows 10.0.19045 (x86_64)\n")!!
        assertEquals("1.0.41", v.protocol)
        assertEquals("35.0.0-12147458", v.build)
        assertEquals("1.0.41 (35.0.0-12147458)", v.toString())
        assertNull(AdbParsers.parseVersion("hello"))
        assertNull(AdbParsers.parseVersion(""))
    }

    @Test
    fun `adb's own error messages map to Appendix A codes`() {
        fun code(text: String) = AdbErrors.classify(text)?.code
        assertEquals(ErrorCodes.DEVICE_UNAUTHORIZED, code("error: device unauthorized.\nThis adb server's \$ADB_VENDOR_KEYS is not set"))
        assertEquals(ErrorCodes.DEVICE_OFFLINE, code("error: device offline"))
        assertEquals(ErrorCodes.NO_DEVICE, code("error: no devices/emulators found"))
        assertEquals(ErrorCodes.NO_DEVICE, code("adb: device 'ABC' not found"))
        assertEquals(ErrorCodes.ADB_START_FAILED, code("adb: error: cannot connect to daemon at tcp:5037: Connection refused"))
        assertEquals(ErrorCodes.ADB_START_FAILED, code("* failed to start daemon"))
        assertEquals(ErrorCodes.NO_PERMISSIONS, code("error: insufficient permissions for device"))
        assertEquals(ErrorCodes.ADB_VERSION_MISMATCH, code("adb server version (41) doesn't match this client (40); killing..."))
        assertNull(code("some ordinary screen text"))
        assertEquals("error: device offline", AdbErrors.classify("\n\nerror: device offline\nmore")?.detail)
    }
}
