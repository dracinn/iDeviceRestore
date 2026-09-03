package com.idevicerestore.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Recovery-mode image upload transport modeled after libirecovery's irecv_send_buffer().
 *
 * This class is intentionally Recovery-only. DFU/WTF uploads use Apple's DFU control-transfer
 * protocol and must not be routed through this bulk endpoint path.
 */
class RecoveryUploadTransport(
    private val connection: UsbDeviceConnection,
    private val bulkOut: UsbEndpoint
) {
    data class Progress(
        val bytesSent: Long,
        val totalBytes: Long,
        val packetIndex: Int
    ) {
        val percent: Int
            get() = if (totalBytes <= 0L) 100 else ((bytesSent * 100L) / totalBytes).toInt().coerceIn(0, 100)
    }

    data class Result(
        val bytesSent: Long,
        val packetsSent: Int,
        val endpointAddress: Int
    )

    init {
        require(bulkOut.direction == UsbConstants.USB_DIR_OUT) {
            "Recovery upload endpoint must be OUT"
        }
        require(bulkOut.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
            "Recovery upload endpoint must be bulk"
        }
        require(bulkOut.address == LIBIRECOVERY_RECOVERY_BULK_OUT_ENDPOINT) {
            "Expected libirecovery Recovery bulk-OUT endpoint 0x%02X, got 0x%02X"
                .format(LIBIRECOVERY_RECOVERY_BULK_OUT_ENDPOINT, bulkOut.address)
        }
    }

    /** Uploads an in-memory component using libirecovery's 0x8000-byte Recovery packet size. */
    fun sendBuffer(
        buffer: ByteArray,
        onProgress: ((Progress) -> Unit)? = null
    ): Result = sendStream(ByteArrayInputStream(buffer), buffer.size.toLong(), onProgress)

    /**
     * Streams exactly [length] bytes to iBoot over Recovery bulk endpoint 0x04.
     *
     * Upstream libirecovery uses 0x8000-byte packets in Recovery mode and treats a short USB write
     * as an upload failure. This method follows the same behavior while allowing large components
     * to be streamed without loading the entire image into memory.
     */
    fun sendStream(
        input: InputStream,
        length: Long,
        onProgress: ((Progress) -> Unit)? = null
    ): Result {
        require(length >= 0L) { "Recovery upload length must be non-negative" }

        if (length == 0L) {
            onProgress?.invoke(Progress(0, 0, 0))
            return Result(0, 0, bulkOut.address)
        }

        val packet = ByteArray(RECOVERY_PACKET_SIZE)
        var remaining = length
        var sent = 0L
        var packetIndex = 0

        while (remaining > 0L) {
            val wanted = minOf(packet.size.toLong(), remaining).toInt()
            readExactly(input, packet, wanted)

            val written = connection.bulkTransfer(
                bulkOut,
                packet,
                0,
                wanted,
                USB_TIMEOUT_MS
            )
            if (written < 0) {
                throw IOException(
                    "Recovery bulk upload failed at packet $packetIndex: endpoint=0x%02X result=%d"
                        .format(bulkOut.address, written)
                )
            }
            if (written != wanted) {
                throw IOException(
                    "Recovery bulk upload short write at packet $packetIndex: expected $wanted, got $written"
                )
            }

            sent += written.toLong()
            remaining -= written.toLong()
            packetIndex++
            onProgress?.invoke(Progress(sent, length, packetIndex))
        }

        return Result(sent, packetIndex, bulkOut.address)
    }

    private fun readExactly(input: InputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) {
                throw EOFException("Recovery component ended early: needed ${length - offset} more byte(s)")
            }
            if (read == 0) continue
            offset += read
        }
    }

    companion object {
        const val RECOVERY_PACKET_SIZE = 0x8000
        const val LIBIRECOVERY_RECOVERY_BULK_OUT_ENDPOINT = 0x04
        private const val USB_TIMEOUT_MS = 10_000
    }
}
