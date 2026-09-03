package com.idevicerestore.android

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.nio.charset.StandardCharsets

/** Minimal, non-destructive iBoot/recovery transport modeled after libirecovery. */
class RecoveryTransport(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint?
) {
    fun sendCommand(command: String): Int {
        require(command.none { it == '\u0000' })
        val bytes = (command + "\u0000").toByteArray(StandardCharsets.US_ASCII)
        return connection.controlTransfer(0x40, 0, 0, 0, bytes, bytes.size, 10_000)
    }

    fun readConsole(timeoutMs: Int = 1200): String {
        val ep = bulkIn ?: return "(no bulk IN endpoint exposed on claimed interface)"
        val buf = ByteArray(16 * 1024)
        val n = connection.bulkTransfer(ep, buf, buf.size, timeoutMs)
        if (n <= 0) return "(no response within ${timeoutMs}ms)"
        return String(buf, 0, n, StandardCharsets.UTF_8).trimEnd('\u0000', '\r', '\n')
    }
}
