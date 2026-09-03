package com.idevicerestore.android

import android.hardware.usb.UsbDeviceConnection
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Apple DFU upload transport modeled after libirecovery's DFU branch of irecv_send_buffer().
 *
 * This is intentionally separate from RecoveryUploadTransport: Recovery uses bulk endpoint 0x04,
 * while DFU uses class/interface control transfers on endpoint zero.
 */
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
        val finalState: Int
    )

    init {
        require(interfaceId in 0..255) { "DFU interface id must be between 0 and 255" }
    }

    fun sendBuffer(
        data: ByteArray,
        onProgress: ((Progress) -> Unit)? = null
    ): Result = sendStream(ByteArrayInputStream(data), data.size.toLong(), onProgress)

    /**
     * Sends exactly [length] bytes using DFU_DNLOAD blocks of 0x800 bytes.
     *
     * Each block is followed by DFU_GETSTATUS polling until the device reports dfuDNLOAD-IDLE.
     * The terminating zero-length DNLOAD is deliberately not sent here; callers should only
     * request manifestation as part of a validated boot-component transition.
     */
    fun sendStream(
        input: InputStream,
        length: Long,
        onProgress: ((Progress) -> Unit)? = null
    ): Result {
        require(length >= 0L) { "DFU upload length must be non-negative" }
        if (length == 0L) return Result(0L, 0, readState())

        ensureDownloadReady()

        val buffer = ByteArray(DFU_PACKET_SIZE)
        var sent = 0L
        var block = 0

        while (sent < length) {
            val wanted = minOf(DFU_PACKET_SIZE.toLong(), length - sent).toInt()
            readExactly(input, buffer, wanted)
            dnload(block, buffer, wanted)
            val status = waitForDownloadIdle()
            sent += wanted.toLong()
            block++
            onProgress?.invoke(Progress(sent, length, block, status.state))
        }

        return Result(sent, block, readState())
    }

    /**
     * Sends the zero-length DFU_DNLOAD request that ends the transfer and begins manifestation.
     * This is state-changing and should only be called after the correct personalized image has
     * been uploaded and the restore coordinator is prepared for USB re-enumeration.
     */
    fun finishManifestation(block: Int): Int {
        require(block in 0..0xFFFF) { "DFU block number out of range" }
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
        return waitForManifestation().state
    }

    fun abort() {
        val result = connection.controlTransfer(
            DFU_REQUEST_TYPE_OUT,
            DFU_ABORT,
            0,
            interfaceId,
            null,
            0,
            USB_TIMEOUT_MS
        )
        if (result < 0) throw IOException("DFU_ABORT failed: $result")
    }

    fun clearStatus() {
        val result = connection.controlTransfer(
            DFU_REQUEST_TYPE_OUT,
            DFU_CLRSTATUS,
            0,
            interfaceId,
            null,
            0,
            USB_TIMEOUT_MS
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
            DFU_REQUEST_TYPE_OUT,
            DFU_DNLOAD,
            block,
            interfaceId,
            data,
            length,
            USB_TIMEOUT_MS
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
            if (status.pollTimeoutMs > 0) Thread.sleep(status.pollTimeoutMs.toLong().coerceAtMost(MAX_POLL_SLEEP_MS))
            when (status.state) {
                DFU_DNLOAD_IDLE -> return status
                DFU_DNLOAD_SYNC, DFU_DNLOAD_BUSY -> Unit
                DFU_ERROR -> throw IOException("DFU entered error state during download")
                else -> throw IOException("Unexpected DFU state during download: ${DfuTransport.stateName(status.state)}")
            }
        }
        throw IOException("Timed out waiting for dfuDNLOAD-IDLE")
    }

    private fun waitForManifestation(): DfuStatus {
        var last = getStatus()
        repeat(MAX_STATUS_POLLS) {
            last = getStatus()
            if (last.status != 0) {
                throw IOException("DFU manifestation failed with ${DfuTransport.statusName(last.status)}")
            }
            if (last.pollTimeoutMs > 0) Thread.sleep(last.pollTimeoutMs.toLong().coerceAtMost(MAX_POLL_SLEEP_MS))
            when (last.state) {
                DFU_MANIFEST_SYNC, DFU_MANIFEST, DFU_MANIFEST_WAIT_RESET -> return last
                DFU_DNLOAD_SYNC, DFU_DNLOAD_BUSY, DFU_DNLOAD_IDLE -> Unit
                else -> return last
            }
        }
        return last
    }

    private data class DfuStatus(val status: Int, val pollTimeoutMs: Int, val state: Int)

    private fun getStatus(): DfuStatus {
        val data = ByteArray(6)
        val n = connection.controlTransfer(
            DFU_REQUEST_TYPE_IN,
            DFU_GETSTATUS,
            0,
            interfaceId,
            data,
            data.size,
            USB_TIMEOUT_MS
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
            DFU_REQUEST_TYPE_IN,
            DFU_GETSTATE,
            0,
            interfaceId,
            data,
            data.size,
            USB_TIMEOUT_MS
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

    companion object {
        const val DFU_PACKET_SIZE = 0x800

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
        private const val DFU_MANIFEST_SYNC = 6
        private const val DFU_MANIFEST = 7
        private const val DFU_MANIFEST_WAIT_RESET = 8
        private const val DFU_ERROR = 10

        private const val USB_TIMEOUT_MS = 10_000
        private const val MAX_STATUS_POLLS = 128
        private const val MAX_POLL_SLEEP_MS = 5_000L
    }
}
