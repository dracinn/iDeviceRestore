package com.idevicerestore.android

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.io.File

/**
 * Explicit, user-triggerable TSS signing session.
 *
 * Reads live DFU nonces, rebuilds the selected BuildIdentity, constructs the AP/Image4 request,
 * and asks Apple TSS for an ApImg4Ticket. It never uploads firmware or changes DFU state.
 */
class TssSigningSession(
    private val logger: (String) -> Unit = {}
) {
    data class Result(
        val foundation: TssRequestFoundation.Parameters,
        val request: TssRequestBuilder.Result,
        val response: TssHttpTransport.Response
    ) {
        val apImg4Ticket: ByteArray
            get() = response.apImg4Ticket ?: error("TSS response has no ApImg4Ticket")

        fun summary(): String =
            "${foundation.summary()} | ${request.summary()} | ${response.summary()}"
    }

    fun requestApImg4Ticket(
        device: UsbDevice,
        connection: UsbDeviceConnection,
        ipsw: File,
        preflight: IpswPreflight.Result
    ): Result {
        require(AppleUsb.mode(device) == AppleUsb.Mode.DFU) {
            "TSS signing session requires a live DFU device"
        }
        require(ipsw.isFile) { "IPSW not found: ${ipsw.absolutePath}" }

        logger("TSS signing: reading live DFU nonce descriptor")
        val nonces = DfuTransport(connection).getNonceInfo()
        logger(nonces.summary())

        val readiness = TssRequestFoundation.build(device, preflight, nonces)
        val foundation = readiness.parameters ?: error(
            "TSS signing prerequisites unavailable: ${readiness.reasons.joinToString("; ")}"
        )
        logger(foundation.summary())

        val identity = IpswBuildIdentityReader(logger).read(ipsw, preflight.identityIndex)
        val request = TssRequestBuilder.build(foundation, identity)
        logger(request.summary())

        val response = TssHttpTransport(logger).send(request)
        if (!response.success) {
            error("Apple TSS rejected signing request: status=${response.status} message=${response.message}")
        }
        logger("TSS signing: ApImg4Ticket received (${response.apImg4Ticket!!.size} bytes)")
        return Result(foundation, request, response)
    }
}
