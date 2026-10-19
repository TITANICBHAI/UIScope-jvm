package com.titanicbhai.uiscope.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class ScreencapManager(private val adbManager: AdbManager) {

    suspend fun capture(serial: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = adbManager.captureScreenshotBytes(serial)
            if (bytes.isEmpty()) error("Empty screenshot bytes — screen may be locked")
            if (!isPng(bytes)) error("Invalid PNG header — device may be off or screen locked")
            bytes
        }
    }

    fun pollFlow(serial: String, intervalMs: Long = 2000L): Flow<Result<ByteArray>> = flow {
        while (true) {
            emit(capture(serial))
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return sig.indices.all { bytes[it] == sig[it] }
    }
}
