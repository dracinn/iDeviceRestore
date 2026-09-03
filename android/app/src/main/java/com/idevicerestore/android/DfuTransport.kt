package com.idevicerestore.android

import android.hardware.usb.UsbDeviceConnection

/** USB DFU class requests used only for status probing in milestone 1. */
class DfuTransport(private val connection: UsbDeviceConnection) {
    data class Status(
        val status: Int,
        val pollTimeoutMs: Int,
        val state: Int,
        val iString: Int,
        val raw: ByteArray
    )

    fun getStatus(): Status {
        val data = ByteArray(6)
        val n = connection.controlTransfer(0xA1, 3, 0, 0, data, data.size, 5_000)
        check(n == 6) { "DFU_GETSTATUS returned $n bytes" }
        val poll = (data[1].toInt() and 0xff) or
            ((data[2].toInt() and 0xff) shl 8) or
            ((data[3].toInt() and 0xff) shl 16)
        return Status(
            status = data[0].toInt() and 0xff,
            pollTimeoutMs = poll,
            state = data[4].toInt() and 0xff,
            iString = data[5].toInt() and 0xff,
            raw = data
        )
    }
}
