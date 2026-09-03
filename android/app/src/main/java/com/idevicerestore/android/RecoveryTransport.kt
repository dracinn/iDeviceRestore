package com.idevicerestore.android

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Minimal, non-destructive iBoot/recovery transport modeled after libirecovery. */
class RecoveryTransport(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint?
) {
    data class GetEnvResult(
        val commandBytes: Int,
        val responseBytes: Int,
        val value: String
    )

    fun sendCommand(command: String, request: Int = 0): Int {
        require(command.none { it == '\u0000' }) { "Recovery command contains NUL" }
        require(command.toByteArray(StandardCharsets.US_ASCII).size < 0x100) {
            "Recovery command must be shorter than 256 bytes"
        }
        val bytes = (command + "\u0000").toByteArray(StandardCharsets.US_ASCII)
        return connection.controlTransfer(0x40, request, 0, 0, bytes, bytes.size, USB_TIMEOUT_MS)
    }

    /**
     * Mirrors libirecovery's irecv_getenv(): send `getenv <variable>` using a
     * vendor OUT control transfer, then read the value using vendor IN 0xC0.
     */
    fun getenv(variable: String): GetEnvResult {
        require(variable.isNotBlank()) { "Environment variable cannot be blank" }
        require(variable.none { it == '\u0000' }) { "Environment variable contains NUL" }

        val commandBytes = sendCommand("getenv $variable")
        if (commandBytes < 0) {
            throw IOException("Recovery command control transfer failed: $commandBytes")
        }

        val response = ByteArray(256)
        val responseBytes = connection.controlTransfer(
            0xC0,
            0,
            0,
            0,
            response,
            255,
            USB_TIMEOUT_MS
        )
        if (responseBytes < 0) {
            throw IOException("Recovery getenv control-IN transfer failed: $responseBytes")
        }

        val value = if (responseBytes == 0) {
            ""
        } else {
            String(response, 0, responseBytes, StandardCharsets.UTF_8)
                .trimEnd('\u0000', '\r', '\n')
        }
        return GetEnvResult(commandBytes, responseBytes, value)
    }

    /** Optional console diagnostic; getenv responses do not use this path. */
    fun readConsole(firstTimeoutMs: Int = 1200, maxReads: Int = 4): String {
        val ep = bulkIn ?: return "(no bulk IN endpoint on claimed interface)"
        val output = ArrayList<Byte>()
        val buffer = ByteArray(4096)

        repeat(maxReads) { index ->
            val timeout = if (index == 0) firstTimeoutMs else 150
            val n = connection.bulkTransfer(ep, buffer, buffer.size, timeout)
            if (n <= 0) return@repeat
            for (i in 0 until n) output.add(buffer[i])
            if (n < buffer.size) return@repeat
        }

        if (output.isEmpty()) return "(no console data within ${firstTimeoutMs}ms)"
        return output.toByteArray()
            .toString(StandardCharsets.UTF_8)
            .trimEnd('\u0000', '\r', '\n')
    }

    companion object {
        private const val USB_TIMEOUT_MS = 10_000
    }
}
