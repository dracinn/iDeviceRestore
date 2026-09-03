package com.idevicerestore.android

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Minimal, non-destructive iBoot/recovery transport modeled after libirecovery. */
class RecoveryTransport(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint?
) {
    data class CommandResponse(
        val commandBytes: Int,
        val responseBytes: Int,
        val response: ByteArray
    ) {
        fun utf8(): String = if (responseBytes <= 0) {
            ""
        } else {
            String(response, 0, responseBytes, StandardCharsets.UTF_8)
                .trimEnd('\u0000', '\r', '\n')
        }
    }

    data class GetEnvResult(
        val commandBytes: Int,
        val responseBytes: Int,
        val value: String
    )

    /** Mirrors libirecovery's vendor control-OUT command transport. */
    fun sendCommand(command: String, request: Int = 0): Int {
        require(command.none { it == '\u0000' }) { "Recovery command contains NUL" }
        val commandData = command.toByteArray(StandardCharsets.US_ASCII)
        require(commandData.size < 0x100) { "Recovery command must be shorter than 256 bytes" }
        val bytes = ByteArray(commandData.size + 1)
        commandData.copyInto(bytes)

        val written = connection.controlTransfer(
            RECOVERY_REQUEST_TYPE_OUT,
            request,
            0,
            0,
            bytes,
            bytes.size,
            USB_TIMEOUT_MS
        )
        if (written < 0) throw IOException("Recovery command control transfer failed: $written")
        if (written != bytes.size) {
            throw IOException("Recovery command short write: expected ${bytes.size}, got $written")
        }
        return written
    }

    /** Reads the vendor control-IN response used by iBoot command helpers such as getenv. */
    fun readControlResponse(request: Int = 0, maxBytes: Int = 255): ByteArray {
        require(maxBytes in 1..0xFFFF) { "maxBytes must be between 1 and 65535" }
        val response = ByteArray(maxBytes)
        val received = connection.controlTransfer(
            RECOVERY_REQUEST_TYPE_IN,
            request,
            0,
            0,
            response,
            response.size,
            USB_TIMEOUT_MS
        )
        if (received < 0) throw IOException("Recovery control-IN transfer failed: $received")
        return response.copyOf(received)
    }

    /** Performs one vendor control-OUT command followed by its vendor control-IN response. */
    fun exchangeCommand(
        command: String,
        outRequest: Int = 0,
        inRequest: Int = 0,
        maxResponseBytes: Int = 255
    ): CommandResponse {
        val commandBytes = sendCommand(command, outRequest)
        val response = readControlResponse(inRequest, maxResponseBytes)
        return CommandResponse(commandBytes, response.size, response)
    }

    /**
     * Mirrors libirecovery's irecv_getenv(): send `getenv <variable>` using a
     * vendor OUT control transfer, then read the value using vendor IN 0xC0.
     */
    fun getenv(variable: String): GetEnvResult {
        require(variable.isNotBlank()) { "Environment variable cannot be blank" }
        require(variable.none { it == '\u0000' }) { "Environment variable contains NUL" }
        require(variable.none { it.isWhitespace() }) { "Environment variable cannot contain whitespace" }

        val result = exchangeCommand("getenv $variable")
        return GetEnvResult(result.commandBytes, result.responseBytes, result.utf8())
    }

    /** Optional console diagnostic; getenv responses do not use this path. */
    fun readConsole(firstTimeoutMs: Int = 1200, maxReads: Int = 4): String {
        require(firstTimeoutMs >= 0) { "firstTimeoutMs must be non-negative" }
        require(maxReads in 1..32) { "maxReads must be between 1 and 32" }
        val ep = bulkIn ?: return "(no bulk IN endpoint on claimed interface)"
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)

        repeat(maxReads) { index ->
            val timeout = if (index == 0) firstTimeoutMs else 150
            val n = connection.bulkTransfer(ep, buffer, buffer.size, timeout)
            if (n <= 0) return@repeat
            output.write(buffer, 0, n)
            if (n < buffer.size) return@repeat
        }

        if (output.size() == 0) return "(no console data within ${firstTimeoutMs}ms)"
        return output.toByteArray()
            .toString(StandardCharsets.UTF_8)
            .trimEnd('\u0000', '\r', '\n')
    }

    companion object {
        private const val RECOVERY_REQUEST_TYPE_OUT = 0x40
        private const val RECOVERY_REQUEST_TYPE_IN = 0xC0
        private const val USB_TIMEOUT_MS = 10_000
    }
}
