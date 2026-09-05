package com.idevicerestore.android

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * iBoot/recovery USB transport modeled after libirecovery.
 *
 * This class intentionally keeps transport primitives separate from restore policy. Safe command
 * and read helpers live here; higher-level code decides when potentially state-changing commands
 * such as `go`, `reset`, image execution, or environment mutation are appropriate.
 *
 * Android can open more than one UsbDeviceConnection for the same Recovery device. iBoot exposes a
 * single command/response channel, so command traffic must be serialized across transport instances
 * or one caller can consume another caller's control-IN response.
 */
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

    data class ControlTransferResult(
        val requestType: Int,
        val request: Int,
        val value: Int,
        val index: Int,
        val transferred: Int
    )

    /** Mirrors libirecovery's standard irecv_send_command() vendor control-OUT transport. */
    fun sendCommand(command: String): Int = COMMAND_CHANNEL_LOCK.withLock {
        sendCommandInternal(command, IRECV_DEFAULT_COMMAND_REQUEST)
    }

    /**
     * Mirrors libirecovery's irecv_send_command_breq().
     *
     * Apple Silicon restore flows use this for special iBoot commands (notably the M1 iBEC `go`
     * transition with bRequest=1). This primitive does not choose commands on its own.
     */
    fun sendCommandBreq(command: String, request: Int): Int = COMMAND_CHANNEL_LOCK.withLock {
        require(request in 0..0xFF) { "Recovery bRequest must be between 0 and 255" }
        sendCommandInternal(command, request)
    }

    private fun sendCommandInternal(command: String, request: Int): Int {
        require(command.none { it == '\u0000' }) { "Recovery command contains NUL" }
        val commandData = command.toByteArray(StandardCharsets.US_ASCII)
        require(commandData.size < 0x100) { "Recovery command must be shorter than 256 bytes" }

        // libirecovery sends strlen(command)+1, including the trailing NUL byte.
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
    fun readControlResponse(request: Int = IRECV_DEFAULT_COMMAND_REQUEST, maxBytes: Int = 255): ByteArray =
        COMMAND_CHANNEL_LOCK.withLock {
            readControlResponseInternal(request, maxBytes)
        }

    private fun readControlResponseInternal(request: Int, maxBytes: Int): ByteArray {
        require(request in 0..0xFF) { "Recovery bRequest must be between 0 and 255" }
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

    /**
     * Performs one vendor control-OUT command followed by its vendor control-IN response.
     *
     * The shared lock deliberately spans both transfers. Locking only the OUT and IN calls
     * independently would still allow another RecoveryTransport instance to insert a command
     * between them and steal the response.
     */
    fun exchangeCommand(
        command: String,
        outRequest: Int = IRECV_DEFAULT_COMMAND_REQUEST,
        inRequest: Int = IRECV_DEFAULT_COMMAND_REQUEST,
        maxResponseBytes: Int = 255
    ): CommandResponse = COMMAND_CHANNEL_LOCK.withLock {
        require(outRequest in 0..0xFF) { "Recovery bRequest must be between 0 and 255" }
        val commandBytes = sendCommandInternal(command, outRequest)
        val response = readControlResponseInternal(inRequest, maxResponseBytes)
        CommandResponse(commandBytes, response.size, response)
    }

    /**
     * Mirrors libirecovery's irecv_getenv(): send `getenv <variable>` and read the returned value.
     * This helper is read-only with respect to iBoot environment state.
     */
    fun getenv(variable: String): GetEnvResult {
        require(variable.isNotBlank()) { "Environment variable cannot be blank" }
        require(variable.none { it == '\u0000' }) { "Environment variable contains NUL" }
        require(variable.none { it.isWhitespace() }) { "Environment variable cannot contain whitespace" }

        val result = exchangeCommand("getenv $variable")
        return GetEnvResult(result.commandBytes, result.responseBytes, result.utf8())
    }

    /**
     * Low-level USB control transfer primitive equivalent to libirecovery's
     * irecv_usb_control_transfer(). It exists for protocol steps that are not iBoot text commands.
     */
    fun controlTransferOut(
        requestType: Int,
        request: Int,
        value: Int = 0,
        index: Int = 0,
        data: ByteArray? = null,
        timeoutMs: Int = USB_TIMEOUT_MS
    ): ControlTransferResult {
        require(requestType in 0..0xFF) { "requestType must be between 0 and 255" }
        require(request in 0..0xFF) { "request must be between 0 and 255" }
        require(value in 0..0xFFFF) { "value must be between 0 and 65535" }
        require(index in 0..0xFFFF) { "index must be between 0 and 65535" }
        require(timeoutMs >= 0) { "timeoutMs must be non-negative" }
        require(requestType and 0x80 == 0) { "controlTransferOut requires a host-to-device request type" }

        val transferred = connection.controlTransfer(
            requestType,
            request,
            value,
            index,
            data,
            data?.size ?: 0,
            timeoutMs
        )
        if (transferred < 0) {
            throw IOException(
                "Recovery control-OUT failed: type=0x%02X request=0x%02X result=%d"
                    .format(requestType, request, transferred)
            )
        }
        if (data != null && transferred != data.size) {
            throw IOException("Recovery control-OUT short write: expected ${data.size}, got $transferred")
        }
        return ControlTransferResult(requestType, request, value, index, transferred)
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
        const val APPLE_SILICON_GO_BREQUEST = 1

        /** One iBoot command/response stream is shared by all Android connections to Recovery USB. */
        private val COMMAND_CHANNEL_LOCK = ReentrantLock(true)

        private const val IRECV_DEFAULT_COMMAND_REQUEST = 0
        private const val RECOVERY_REQUEST_TYPE_OUT = 0x40
        private const val RECOVERY_REQUEST_TYPE_IN = 0xC0
        private const val USB_TIMEOUT_MS = 10_000
    }
}
