package com.idevicerestore.android

import android.hardware.usb.UsbDeviceConnection

/** Read-only USB DFU class requests used to inspect Apple DFU state. */
class DfuTransport(private val connection: UsbDeviceConnection) {
    data class Status(
        val status: Int,
        val pollTimeoutMs: Int,
        /** Explicit DFU_GETSTATE result. */
        val state: Int,
        /** State byte returned inside DFU_GETSTATUS. */
        val statusState: Int,
        val iString: Int,
        val raw: ByteArray
    ) {
        val statusName: String get() = statusName(status)
        val stateName: String get() = stateName(state)
        val statusStateName: String get() = stateName(statusState)
        val stateConsistent: Boolean get() = state == statusState
    }

    data class State(
        val value: Int,
        val raw: ByteArray
    ) {
        val name: String get() = stateName(value)
    }

    /**
     * Reads both DFU_GETSTATUS and DFU_GETSTATE. Both requests are device-to-host
     * class requests and do not upload firmware or change DFU state.
     */
    fun getStatus(): Status {
        val data = ByteArray(6)
        val n = connection.controlTransfer(
            DFU_REQUEST_TYPE_IN,
            DFU_GETSTATUS,
            0,
            0,
            data,
            data.size,
            USB_TIMEOUT_MS
        )
        check(n == 6) { "DFU_GETSTATUS returned $n bytes" }
        val poll = (data[1].toInt() and 0xff) or
            ((data[2].toInt() and 0xff) shl 8) or
            ((data[3].toInt() and 0xff) shl 16)
        val statusState = data[4].toInt() and 0xff
        val explicitState = getState().value
        return Status(
            status = data[0].toInt() and 0xff,
            pollTimeoutMs = poll,
            state = explicitState,
            statusState = statusState,
            iString = data[5].toInt() and 0xff,
            raw = data
        )
    }

    /** DFU class GETSTATE (bRequest=5). This is a one-byte, read-only state query. */
    fun getState(): State {
        val data = ByteArray(1)
        val n = connection.controlTransfer(
            DFU_REQUEST_TYPE_IN,
            DFU_GETSTATE,
            0,
            0,
            data,
            data.size,
            USB_TIMEOUT_MS
        )
        check(n == 1) { "DFU_GETSTATE returned $n bytes" }
        return State(data[0].toInt() and 0xff, data)
    }

    companion object {
        private const val DFU_REQUEST_TYPE_IN = 0xA1
        private const val DFU_GETSTATUS = 3
        private const val DFU_GETSTATE = 5
        private const val USB_TIMEOUT_MS = 5_000

        fun stateName(state: Int): String = when (state) {
            0 -> "appIDLE"
            1 -> "appDETACH"
            2 -> "dfuIDLE"
            3 -> "dfuDNLOAD-SYNC"
            4 -> "dfuDNBUSY"
            5 -> "dfuDNLOAD-IDLE"
            6 -> "dfuMANIFEST-SYNC"
            7 -> "dfuMANIFEST"
            8 -> "dfuMANIFEST-WAIT-RESET"
            9 -> "dfuUPLOAD-IDLE"
            10 -> "dfuERROR"
            else -> "unknown($state)"
        }

        fun statusName(status: Int): String = when (status) {
            0x00 -> "OK"
            0x01 -> "errTARGET"
            0x02 -> "errFILE"
            0x03 -> "errWRITE"
            0x04 -> "errERASE"
            0x05 -> "errCHECK_ERASED"
            0x06 -> "errPROG"
            0x07 -> "errVERIFY"
            0x08 -> "errADDRESS"
            0x09 -> "errNOTDONE"
            0x0A -> "errFIRMWARE"
            0x0B -> "errVENDOR"
            0x0C -> "errUSBR"
            0x0D -> "errPOR"
            0x0E -> "errUNKNOWN"
            0x0F -> "errSTALLEDPKT"
            else -> "unknown(0x%02X)".format(status)
        }
    }
}
