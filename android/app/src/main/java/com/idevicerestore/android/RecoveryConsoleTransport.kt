package com.idevicerestore.android

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Read-only bulk console channel for classic Apple Recovery mode.
 *
 * The primary iBoot command path remains the vendor-control channel on interface 0.
 * This class temporarily claims a secondary alternate interface that exposes bulk IN,
 * reads any pending console bytes, and releases that interface when closed.
 * It intentionally exposes no bulk-OUT/write operation.
 */
class RecoveryConsoleTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val claimed: AppleUsb.Claimed
) : Closeable {
    data class ReadResult(
        val bytes: Int,
        val text: String,
        val endpointAddress: Int,
        val interfaceId: Int,
        val alternateSetting: Int
    )

    fun read(firstTimeoutMs: Int = 750, maxReads: Int = 4): ReadResult {
        require(firstTimeoutMs >= 0) { "firstTimeoutMs must be >= 0" }
        require(maxReads in 1..32) { "maxReads must be between 1 and 32" }

        val endpoint = claimed.bulkIn
            ?: throw IOException("Recovery console interface has no bulk-IN endpoint")
        val output = ArrayList<Byte>()
        val buffer = ByteArray(4096)

        for (index in 0 until maxReads) {
            val timeout = if (index == 0) firstTimeoutMs else 100
            val count = connection.bulkTransfer(endpoint, buffer, buffer.size, timeout)
            if (count <= 0) break
            for (i in 0 until count) output += buffer[i]
            if (count < buffer.size) break
        }

        val bytes = output.toByteArray()
        val text = if (bytes.isEmpty()) {
            ""
        } else {
            String(bytes, StandardCharsets.UTF_8).trimEnd('\u0000', '\r', '\n')
        }
        return ReadResult(
            bytes = bytes.size,
            text = text,
            endpointAddress = endpoint.address,
            interfaceId = claimed.intf.id,
            alternateSetting = claimed.intf.alternateSetting
        )
    }

    override fun close() {
        connection.releaseInterface(claimed.intf)
    }

    companion object {
        fun open(device: UsbDevice, connection: UsbDeviceConnection): RecoveryConsoleTransport? {
            if (AppleUsb.mode(device) != AppleUsb.Mode.RECOVERY) return null
            val claimed = AppleUsb.claimRecoveryConsoleInterface(device, connection) ?: return null
            return RecoveryConsoleTransport(connection, claimed)
        }
    }
}
