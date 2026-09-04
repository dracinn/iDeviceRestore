package com.idevicerestore.android

import android.hardware.usb.UsbDeviceConnection

/** Read-only USB DFU class/descriptor requests used to inspect Apple DFU state. */
class DfuTransport(private val connection: UsbDeviceConnection) {
    data class Status(
        val status: Int,
        val pollTimeoutMs: Int,
        /** DFU_GETSTATE immediately after DFU_GETSTATUS. */
        val state: Int,
        /** DFU_GETSTATE immediately before DFU_GETSTATUS. */
        val stateBefore: Int,
        /** State byte returned inside DFU_GETSTATUS. */
        val statusState: Int,
        val iString: Int,
        val raw: ByteArray
    ) {
        val statusName: String get() = statusName(status)
        val stateName: String get() = stateName(state)
        val stateBeforeName: String get() = stateName(stateBefore)
        val statusStateName: String get() = stateName(statusState)
        val stateConsistent: Boolean get() = stateBefore == statusState && statusState == state
    }

    data class State(val value: Int, val raw: ByteArray) {
        val name: String get() = stateName(value)
    }

    data class FunctionalDescriptor(
        val attributes: Int,
        val detachTimeoutMs: Int,
        val transferSize: Int,
        val bcdDfuVersion: Int,
        val raw: ByteArray
    ) {
        val canDownload: Boolean get() = attributes and 0x01 != 0
        val canUpload: Boolean get() = attributes and 0x02 != 0
        val manifestationTolerant: Boolean get() = attributes and 0x04 != 0
        val willDetach: Boolean get() = attributes and 0x08 != 0
        val versionText: String get() = "%x.%02x".format((bcdDfuVersion ushr 8) and 0xff, bcdDfuVersion and 0xff)
    }

    /**
     * Samples GETSTATE -> GETSTATUS -> GETSTATE. All three requests are device-to-host and
     * read-only. The extra state sample lets hardware testing detect an unstable DFU state
     * without issuing DNLOAD, CLRSTATUS, ABORT, DETACH, manifestation, or reset requests.
     */
    fun getStatus(): Status {
        val before = getState().value
        val data = ByteArray(6)
        val n = connection.controlTransfer(
            DFU_REQUEST_TYPE_IN, DFU_GETSTATUS, 0, 0, data, data.size, USB_TIMEOUT_MS
        )
        check(n == 6) { "DFU_GETSTATUS returned $n bytes" }
        val poll = (data[1].toInt() and 0xff) or
            ((data[2].toInt() and 0xff) shl 8) or
            ((data[3].toInt() and 0xff) shl 16)
        val statusState = data[4].toInt() and 0xff
        val after = getState().value
        return Status(
            status = data[0].toInt() and 0xff,
            pollTimeoutMs = poll,
            state = after,
            stateBefore = before,
            statusState = statusState,
            iString = data[5].toInt() and 0xff,
            raw = data
        )
    }

    /** DFU class GETSTATE (bRequest=5). This is a one-byte, read-only state query. */
    fun getState(): State {
        val data = ByteArray(1)
        val n = connection.controlTransfer(
            DFU_REQUEST_TYPE_IN, DFU_GETSTATE, 0, 0, data, data.size, USB_TIMEOUT_MS
        )
        check(n == 1) { "DFU_GETSTATE returned $n bytes" }
        return State(data[0].toInt() and 0xff, data)
    }

    fun getNonceInfo(): DfuNonceInfo.Snapshot = DfuNonceInfo.fromConnection(connection)

    fun getFunctionalDescriptor(interfaceId: Int): FunctionalDescriptor {
        require(interfaceId in 0..255) { "interfaceId must be between 0 and 255" }
        val data = ByteArray(9)
        val n = connection.controlTransfer(
            STANDARD_INTERFACE_IN,
            USB_GET_DESCRIPTOR,
            DFU_FUNCTIONAL_DESCRIPTOR_TYPE shl 8,
            interfaceId,
            data,
            data.size,
            USB_TIMEOUT_MS
        )
        check(n >= 9) { "DFU functional descriptor returned $n bytes" }
        check((data[0].toInt() and 0xff) >= 9) { "DFU functional descriptor length is ${data[0].toInt() and 0xff}" }
        check((data[1].toInt() and 0xff) == DFU_FUNCTIONAL_DESCRIPTOR_TYPE) {
            "Unexpected descriptor type 0x%02X".format(data[1].toInt() and 0xff)
        }
        return FunctionalDescriptor(
            attributes = data[2].toInt() and 0xff,
            detachTimeoutMs = littleEndian16(data, 3),
            transferSize = littleEndian16(data, 5),
            bcdDfuVersion = littleEndian16(data, 7),
            raw = data
        )
    }

    companion object {
        private const val DFU_REQUEST_TYPE_IN = 0xA1
        private const val STANDARD_INTERFACE_IN = 0x81
        private const val USB_GET_DESCRIPTOR = 6
        private const val DFU_FUNCTIONAL_DESCRIPTOR_TYPE = 0x21
        private const val DFU_GETSTATUS = 3
        private const val DFU_GETSTATE = 5
        private const val USB_TIMEOUT_MS = 5_000

        private fun littleEndian16(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

        fun stateName(state: Int): String = when (state) {
            0 -> "appIDLE"; 1 -> "appDETACH"; 2 -> "dfuIDLE"; 3 -> "dfuDNLOAD-SYNC"
            4 -> "dfuDNBUSY"; 5 -> "dfuDNLOAD-IDLE"; 6 -> "dfuMANIFEST-SYNC"
            7 -> "dfuMANIFEST"; 8 -> "dfuMANIFEST-WAIT-RESET"; 9 -> "dfuUPLOAD-IDLE"
            10 -> "dfuERROR"; else -> "unknown($state)"
        }

        fun statusName(status: Int): String = when (status) {
            0x00 -> "OK"; 0x01 -> "errTARGET"; 0x02 -> "errFILE"; 0x03 -> "errWRITE"
            0x04 -> "errERASE"; 0x05 -> "errCHECK_ERASED"; 0x06 -> "errPROG"; 0x07 -> "errVERIFY"
            0x08 -> "errADDRESS"; 0x09 -> "errNOTDONE"; 0x0A -> "errFIRMWARE"; 0x0B -> "errVENDOR"
            0x0C -> "errUSBR"; 0x0D -> "errPOR"; 0x0E -> "errUNKNOWN"; 0x0F -> "errSTALLEDPKT"
            else -> "unknown(0x%02X)".format(status)
        }
    }
}
