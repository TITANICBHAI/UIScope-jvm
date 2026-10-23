package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.error.Remedy
import com.titanicbhai.uiscope.process.ProcessRunner
import kotlin.time.Duration.Companion.seconds

/** The result of `adb pair` or `adb connect`. A failure carries adb's own message (shown verbatim) plus hints about the usual causes. */
sealed interface WirelessResult {
    data class Success(val address: String, val message: String, val alreadyConnected: Boolean = false) : WirelessResult

    data class Failure(val code: String, val adbMessage: String, val hints: List<String>) : WirelessResult
}

data class MdnsService(val name: String, val kind: Kind, val address: String) {
    enum class Kind { PAIRING, CONNECT }
}

object WirelessParsers {
    private val paired = Regex("""Successfully paired to (\S+)""")
    private val connected = Regex("""^(already connected|connected) to (\S+)""", RegexOption.IGNORE_CASE)

    val pairHints = listOf(
        "The phone and this computer must be on the same Wi-Fi network.",
        "The pairing code expires: open Pair device with pairing code on the phone again for a new one.",
        "Use the IP address and port shown on the pairing dialog; the connection port is a different one.",
    )
    val connectHints = listOf(
        "Check the IP address and port on the phone's Wireless debugging screen.",
        "The port changes every time wireless debugging is switched on.",
        "Pair the phone first if you have not done so yet.",
    )

    fun parsePair(output: String): WirelessResult {
        paired.find(output)?.let { return WirelessResult.Success(it.groupValues[1], AdbErrors.firstLine(output)) }
        return WirelessResult.Failure(ErrorCodes.WIRELESS_PAIR_FAILED, AdbErrors.firstLine(output).ifEmpty { "adb gave no message" }, pairHints)
    }

    fun parseConnect(output: String): WirelessResult {
        for (line in output.lineSequence().map { it.trim() }) {
            connected.find(line)?.let {
                return WirelessResult.Success(it.groupValues[2], line, alreadyConnected = it.groupValues[1].startsWith("already", ignoreCase = true))
            }
        }
        return WirelessResult.Failure(ErrorCodes.WIRELESS_CONNECT_FAILED, AdbErrors.firstLine(output).ifEmpty { "adb gave no message" }, connectHints)
    }

    /** `adb mdns services`: a header line, then `name <TAB> type <TAB> address` rows. Only the two adb TLS service types are kept. */
    fun parseMdns(output: String): List<MdnsService> =
        output.lineSequence().map { it.trim().split(Regex("\\s+")) }.filter { it.size >= 3 }.mapNotNull { f ->
            val kind = when {
                f[1].startsWith("_adb-tls-pairing") -> MdnsService.Kind.PAIRING
                f[1].startsWith("_adb-tls-connect") -> MdnsService.Kind.CONNECT
                else -> return@mapNotNull null
            }
            MdnsService(f[0], kind, f[2])
        }.toList()
}

/** Wireless debugging commands. Addresses and codes are validated first; the pairing code is never logged (the runner logs no arguments). */
class AdbWifi(private val adb: String, private val runner: ProcessRunner) {
    private val hostPort = Regex("""^[A-Za-z0-9.\-]+:(\d{1,5})$""")

    suspend fun pair(address: String, code: String): Outcome<WirelessResult> {
        if (!validAddress(address)) return Outcome.Success(invalid(ErrorCodes.WIRELESS_PAIR_FAILED, "That is not an address like 192.168.1.20:37099", WirelessParsers.pairHints))
        if (!Regex("""\d{6}""").matches(code)) return Outcome.Success(invalid(ErrorCodes.WIRELESS_PAIR_FAILED, "The pairing code is 6 digits", WirelessParsers.pairHints))
        return output(listOf(adb, "pair", address, code), 15) { WirelessParsers.parsePair(it) }
    }

    suspend fun connect(address: String): Outcome<WirelessResult> {
        if (!validAddress(address)) return Outcome.Success(invalid(ErrorCodes.WIRELESS_CONNECT_FAILED, "That is not an address like 192.168.1.20:5555", WirelessParsers.connectHints))
        return output(listOf(adb, "connect", address), 15) { WirelessParsers.parseConnect(it) }
    }

    suspend fun disconnect(address: String): Outcome<Unit> =
        if (!validAddress(address)) Outcome.Success(Unit) else output(listOf(adb, "disconnect", address), 5) { Unit }

    suspend fun mdnsServices(): Outcome<List<MdnsService>> = output(listOf(adb, "mdns", "services"), 5) { WirelessParsers.parseMdns(it) }

    private fun validAddress(address: String): Boolean = hostPort.matchEntire(address)?.groupValues?.get(1)?.toIntOrNull()?.let { it in 1..65535 } ?: false

    private fun invalid(code: String, message: String, hints: List<String>) = WirelessResult.Failure(code, message, hints)

    private suspend fun <T> output(cmd: List<String>, seconds: Int, parse: (String) -> T): Outcome<T> =
        when (val r = runner.run(cmd, seconds.seconds)) {
            is Outcome.Failure -> when (r.error.code) {
                ErrorCodes.PROCESS_START_FAILED -> Outcome.Failure(CodedError(ErrorCodes.ADB_NOT_FOUND, "adb could not be started", Remedy.RETRY))
                else -> r
            }
            is Outcome.Success -> Outcome.Success(parse(r.value.stdoutText() + "\n" + r.value.stderrText()))
        }
}
