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

    /** Issues one libirecovery Recovery upload initialization request; no bulk bytes are sent. */
    fun initializeUpload(): Int = connection.controlTransfer(
        LIBIRECOVERY_UPLOAD_INIT_REQUEST_TYPE,
        LIBIRECOVERY_UPLOAD_INIT_REQUEST,
        0,
        0,
        null,
        0,
        USB_TIMEOUT_MS
    )

    /** Uploads an in-memory component using libirecovery's 0x8000-byte Recovery packet size. */
    fun sendBuffer(
        buffer: ByteArray,
        onProgress: ((Progress) -> Unit)? = null
    ): Result = sendStream(ByteArrayInputStream(buffer), buffer.size.toLong(), onProgress)

    /**
     * Streams exactly [length] bytes to iBoot over Recovery bulk endpoint 0x04.
     *
     * Upstream libirecovery primes each Recovery upload with a zero-length 0x41/0 control-OUT
     * request before sending any bulk data, then uses 0x8000-byte packets and treats a short USB
     * write as an upload failure. This method follows that sequence while allowing large components
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

        val initResult = initializeUpload()
        if (initResult < 0) {
            throw IOException(
                "Recovery upload initialization failed: type=0x%02X request=0x%02X value=0 index=0 timeoutMs=%d result=%d"
                    .format(
                        LIBIRECOVERY_UPLOAD_INIT_REQUEST_TYPE,
                        LIBIRECOVERY_UPLOAD_INIT_REQUEST,
                        USB_TIMEOUT_MS,
                        initResult
                    )
            )
        }
        return sendBulkStream(input, length, "initResult=$initResult", onProgress)
    }

    /**
     * Sends bulk data without issuing 0x41/0 again.
     *
     * This is only for the M1 split-phase path after a same-device upload-init re-enumeration has
     * already been proven and consumed by the caller. Ordinary Recovery uploads must use [sendStream].
     */
    internal fun sendStreamAfterInitReenumeration(
        input: InputStream,
        length: Long,
        onProgress: ((Progress) -> Unit)? = null
    ): Result {
        require(length > 0L) { "Post-init Recovery upload length must be positive" }
        return sendBulkStream(input, length, "initResult=external-reenumeration-proof", onProgress)
    }

    private fun sendBulkStream(
        input: InputStream,
        length: Long,
        initEvidence: String,
        onProgress: ((Progress) -> Unit)?
    ): Result {
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
                    "Recovery bulk upload failed at packet $packetIndex: $initEvidence endpoint=0x%02X type=%d maxPacket=%d requested=%d timeoutMs=%d result=%d"
                        .format(
                            bulkOut.address,
                            bulkOut.type,
                            bulkOut.maxPacketSize,
                            wanted,
                            USB_TIMEOUT_MS,
                            written
                        )
                )
            }
            if (written != wanted) {
                throw IOException(
                    "Recovery bulk upload short write at packet $packetIndex: $initEvidence endpoint=0x%02X maxPacket=%d expected=%d got=%d"
                        .format(bulkOut.address, bulkOut.maxPacketSize, wanted, written)
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
        private const val LIBIRECOVERY_UPLOAD_INIT_REQUEST_TYPE = 0x41
        private const val LIBIRECOVERY_UPLOAD_INIT_REQUEST = 0x00
        private const val USB_TIMEOUT_MS = 10_000
    }
}
