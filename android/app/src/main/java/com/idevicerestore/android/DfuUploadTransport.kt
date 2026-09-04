package com.idevicerestore.android

import android.hardware.usb.UsbDeviceConnection
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** Apple DFU upload transport modeled after libirecovery's DFU irecv_send_buffer path. */
class DfuUploadTransport(
    private val connection: UsbDeviceConnection,
    private val interfaceId: Int
) {
    data class Progress(
        val bytesSent: Long,
        val totalBytes: Long,
        val block: Int,
        val state: Int
    ) {
        val percent: Int
            get() = if (totalBytes <= 0L) 100 else ((bytesSent * 100L) / totalBytes).toInt().coerceIn(0, 100)
    }

    data class Result(
        val bytesSent: Long,
        val blocksSent: Int,
        val finalState: Int,
        val appleSuffixBytes: Int = APPLE_DFU_TRAILER_SIZE
    )

    init {
        require(interfaceId in 0..255) { "DFU interface id must be between 0 and 255" }
    }

    fun sendBuffer(data: ByteArray, onProgress: ((Progress) -> Unit)? = null): Result =
        sendStream(ByteArrayInputStream(data), data.size.toLong(), onProgress)

    fun sendStream(
        input: InputStream,
        length: Long,
        onProgress: ((Progress) -> Unit)? = null
    ): Result {
        require(length > 0L) { "Apple DFU upload length must be positive" }
        require(length <= Int.MAX_VALUE.toLong() * DFU_PACKET_SIZE.toLong()) { "DFU upload is too large" }

        ensureDownloadReady()

        val packetCount = ((length + DFU_PACKET_SIZE - 1L) / DFU_PACKET_SIZE.toLong()).toInt()
        val payload = ByteArray(DFU_PACKET_SIZE)
        var sentPayload = 0L
        var crc = 0xFFFF_FFFFu

        for (block in 0 until packetCount) {
            val wanted = minOf(DFU_PACKET_SIZE.toLong(), length - sentPayload).toInt()
            readExactly(input, payload, wanted)
            for (i in 0 until wanted) crc = crc32Step(crc, payload[i])

            val isLast = block == packetCount - 1
            if (!isLast) {
                dnload(block, payload, wanted)
                val status = waitForDownloadIdle()
                sentPayload += wanted.toLong()
                onProgress?.invoke(Progress(sentPayload, length, block + 1, status.state))
                continue
            }

            for (byte in APPLE_DFU_SUFFIX) crc = crc32Step(crc, byte)
            val trailer = buildAppleTrailer(crc)

            val status = if (wanted + trailer.size > DFU_PACKET_SIZE) {
                // libirecovery uses the same final block number for a separately transmitted trailer.
                dnload(block, payload, wanted)
                dnload(block, trailer, trailer.size)
                waitForDownloadIdle()
            } else {
                val framed = ByteArray(wanted + trailer.size)
                System.arraycopy(payload, 0, framed, 0, wanted)
                System.arraycopy(trailer, 0, framed, wanted, trailer.size)
                dnload(block, framed, framed.size)
                waitForDownloadIdle()
            }

            sentPayload += wanted.toLong()
            onProgress?.invoke(Progress(sentPayload, length, block + 1, status.state))
        }

        return Result(sentPayload, packetCount, readState())
    }

    /**
     * Mirrors IRECV_SEND_OPT_DFU_NOTIFY_FINISH for non-Windows libirecovery:
     * zero-length DFU_DNLOAD using the next block number, GETSTATUS twice, then host USB reset.
     * This method is state-changing and may make the device immediately re-enumerate.
     */
    fun finishManifestation(block: Int): Int {
        require(block in 0..0xFFFF) { "DFU block number out of range" }
        val capability = AndroidUsbReset.capability(connection)
        check(capability.available) { capability.reason }

        val written = connection.controlTransfer(
            DFU_REQUEST_TYPE_OUT,
            DFU_DNLOAD,
            block,
            interfaceId,
            null,
            0,
            USB_TIMEOUT_MS
        )
        if (written < 0) throw IOException("DFU zero-length DNLOAD failed: $written")

        var last = DfuStatus(0, 0, readState())
        repeat(2) {
            last = getStatus()
            if (last.status != 0) {
                throw IOException(
                    "DFU manifestation status error 0x%02X (%s) in state %s"
                        .format(last.status, DfuTransport.statusName(last.status), DfuTransport.stateName(last.state))
                )
            }
            if (last.pollTimeoutMs > 0) {
                Thread.sleep(last.pollTimeoutMs.toLong().coerceAtMost(MAX_POLL_SLEEP_MS))
            }
        }

        AndroidUsbReset.reset(connection)
        return last.state
    }

    fun abort() {
        val result = connection.controlTransfer(
            DFU_REQUEST_TYPE_OUT, DFU_ABORT, 0, interfaceId, null, 0, USB_TIMEOUT_MS
        )
        if (result < 0) throw IOException("DFU_ABORT failed: $result")
    }

    fun clearStatus() {
        val result = connection.controlTransfer(
            DFU_REQUEST_TYPE_OUT, DFU_CLRSTATUS, 0, interfaceId, null, 0, USB_TIMEOUT_MS
        )
        if (result < 0) throw IOException("DFU_CLRSTATUS failed: $result")
    }

    private fun ensureDownloadReady() {
        val state = readState()
        when (state) {
            DFU_IDLE, DFU_DNLOAD_IDLE -> Unit
            DFU_ERROR -> {
                clearStatus()
                val retry = readState()
                check(retry == DFU_IDLE || retry == DFU_DNLOAD_IDLE) {
                    "DFU did not recover after CLRSTATUS: ${DfuTransport.stateName(retry)}"
                }
            }
            else -> {
                abort()
                val retry = readState()
                check(retry == DFU_IDLE || retry == DFU_DNLOAD_IDLE) {
                    "DFU not ready for download after ABORT: ${DfuTransport.stateName(retry)}"
                }
            }
        }
    }

    private fun dnload(block: Int, data: ByteArray, length: Int) {
        require(block in 0..0xFFFF) { "DFU block number out of range" }
        val written = connection.controlTransfer(
            DFU_REQUEST_TYPE_OUT, DFU_DNLOAD, block, interfaceId, data, length, USB_TIMEOUT_MS
        )
        if (written < 0) throw IOException("DFU_DNLOAD block $block failed: $written")
        if (written != length) {
            throw IOException("DFU_DNLOAD block $block short write: expected $length, got $written")
        }
    }

    private fun waitForDownloadIdle(): DfuStatus {
        repeat(MAX_STATUS_POLLS) {
            val status = getStatus()
            if (status.status != 0) {
                throw IOException(
                    "DFU status error 0x%02X (%s) in state %s"
                        .format(status.status, DfuTransport.statusName(status.status), DfuTransport.stateName(status.state))
                )
            }
            if (status.pollTimeoutMs > 0) {
                Thread.sleep(status.pollTimeoutMs.toLong().coerceAtMost(MAX_POLL_SLEEP_MS))
            }
            when (status.state) {
                DFU_DNLOAD_IDLE -> return status
                DFU_DNLOAD_SYNC, DFU_DNLOAD_BUSY -> Unit
                DFU_ERROR -> throw IOException("DFU entered error state during download")
                else -> throw IOException("Unexpected DFU state during download: ${DfuTransport.stateName(status.state)}")
            }
        }
        throw IOException("Timed out waiting for dfuDNLOAD-IDLE")
    }

    private data class DfuStatus(val status: Int, val pollTimeoutMs: Int, val state: Int)

    private fun getStatus(): DfuStatus {
        val data = ByteArray(6)
        val n = connection.controlTransfer(
            DFU_REQUEST_TYPE_IN, DFU_GETSTATUS, 0, interfaceId, data, data.size, USB_TIMEOUT_MS
        )
        if (n != 6) throw IOException("DFU_GETSTATUS returned $n bytes")
        val poll = (data[1].toInt() and 0xFF) or
            ((data[2].toInt() and 0xFF) shl 8) or
            ((data[3].toInt() and 0xFF) shl 16)
        return DfuStatus(data[0].toInt() and 0xFF, poll, data[4].toInt() and 0xFF)
    }

    private fun readState(): Int {
        val data = ByteArray(1)
        val n = connection.controlTransfer(
            DFU_REQUEST_TYPE_IN, DFU_GETSTATE, 0, interfaceId, data, data.size, USB_TIMEOUT_MS
        )
        if (n != 1) throw IOException("DFU_GETSTATE returned $n bytes")
        return data[0].toInt() and 0xFF
    }

    private fun readExactly(input: InputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) throw EOFException("DFU component ended early: needed ${length - offset} more byte(s)")
            if (read == 0) continue
            offset += read
        }
    }

    private fun buildAppleTrailer(crc: UInt): ByteArray {
        val trailer = ByteArray(APPLE_DFU_TRAILER_SIZE)
        System.arraycopy(APPLE_DFU_SUFFIX, 0, trailer, 0, APPLE_DFU_SUFFIX.size)
        trailer[12] = (crc and 0xFFu).toByte()
        trailer[13] = ((crc shr 8) and 0xFFu).toByte()
        trailer[14] = ((crc shr 16) and 0xFFu).toByte()
        trailer[15] = ((crc shr 24) and 0xFFu).toByte()
        return trailer
    }

    private fun crc32Step(current: UInt, byte: Byte): UInt {
        var crc = current xor (byte.toUInt() and 0xFFu)
        repeat(8) {
            crc = if ((crc and 1u) != 0u) (crc shr 1) xor CRC32_POLYNOMIAL else crc shr 1
        }
        return crc
    }

    companion object {
        const val DFU_PACKET_SIZE = 0x800
        const val APPLE_DFU_TRAILER_SIZE = 16

        private val APPLE_DFU_SUFFIX = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xAC.toByte(), 0x05, 0x00, 0x01,
            0x55, 0x46, 0x44, 0x10
        )
        private val CRC32_POLYNOMIAL = 0xEDB88320u

        private const val DFU_REQUEST_TYPE_OUT = 0x21
        private const val DFU_REQUEST_TYPE_IN = 0xA1
        private const val DFU_DNLOAD = 1
        private const val DFU_GETSTATUS = 3
        private const val DFU_CLRSTATUS = 4
        private const val DFU_GETSTATE = 5
        private const val DFU_ABORT = 6

        private const val DFU_IDLE = 2
        private const val DFU_DNLOAD_SYNC = 3
        private const val DFU_DNLOAD_BUSY = 4
        private const val DFU_DNLOAD_IDLE = 5
        private const val DFU_ERROR = 10

        private const val USB_TIMEOUT_MS = 10_000
        private const val MAX_STATUS_POLLS = 128
        private const val MAX_POLL_SLEEP_MS = 5_000L
    }
}
