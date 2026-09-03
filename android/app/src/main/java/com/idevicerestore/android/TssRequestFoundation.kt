package com.idevicerestore.android

import android.hardware.usb.UsbDevice

/**
 * Immutable, non-network TSS request foundation.
 *
 * This class mirrors the core device-bound parameters assembled by idevicerestore before
 * tss_request_add_common_tags()/tss_request_add_ap_tags(). It deliberately does not serialize a
 * request or contact Apple's signing service yet; that remains gated on exact manifest-tag and
 * Image4 personalization parity with upstream.
 */
object TssRequestFoundation {
    data class Parameters(
        val ecid: ULong,
        val apChipId: Long,
        val apBoardId: Long,
        val apSecurityDomain: Long,
        val apProductionMode: Boolean,
        val apSecurityMode: Boolean,
        val apSupportsImg4: Boolean,
        val apNonce: ByteArray,
        val apSepNonce: ByteArray?,
        val identityIndex: Int,
        val boardConfig: String?,
        val productVersion: String?,
        val productBuildVersion: String?
    ) {
        val readyForRequestSerialization: Boolean
            get() = apNonce.isNotEmpty() && apChipId >= 0 && apBoardId >= 0

        /** Privacy-safe summary: ECID and nonce bytes are intentionally omitted. */
        fun summary(): String =
            "TSS foundation: identity=$identityIndex chip=0x${apChipId.toString(16)} " +
                "board=0x${apBoardId.toString(16)} sdom=$apSecurityDomain " +
                "ApNonce=${apNonce.size} bytes ApSepNonce=${apSepNonce?.size ?: 0} bytes " +
                "img4=$apSupportsImg4 build=${productBuildVersion ?: "unknown"}"
    }

    data class Readiness(
        val parameters: Parameters?,
        val reasons: List<String>
    ) {
        val ready: Boolean get() = parameters != null && reasons.isEmpty()
    }

    /**
     * Fallback builder for callers without an open USB connection. A live DFU path should call the
     * overload that accepts [DfuNonceInfo.Snapshot] from DfuNonceInfo.fromConnection().
     */
    fun build(device: UsbDevice, preflight: IpswPreflight.Result): Readiness =
        build(device, preflight, DfuNonceInfo.fromDevice(device))

    fun build(
        device: UsbDevice,
        preflight: IpswPreflight.Result,
        nonces: DfuNonceInfo.Snapshot
    ): Readiness {
        val ids = AppleUsb.bootIdentifiers(device)
        val reasons = mutableListOf<String>()

        val ecid = ids?.ecidHex?.toULongOrNull(16)
        if (ecid == null) reasons += "ECID unavailable"

        val chipId = preflight.chipId ?: ids?.cpid?.toLong()
        if (chipId == null) reasons += "ApChipID unavailable"

        val boardId = preflight.boardId ?: ids?.bdid?.toLong()
        if (boardId == null) reasons += "ApBoardID unavailable"

        // SDOM is present in the Apple boot serial descriptor used for hardware identity.
        val securityDomain = parseHexTag(ids?.rawSerial.orEmpty(), "SDOM")?.toLong()
        if (securityDomain == null) reasons += "ApSecurityDomain unavailable"

        val apNonce = nonces.apNonce
        if (apNonce.isNullOrEmpty()) {
            reasons += "ApNonce (NONC) unavailable from ${nonces.source}"
        }

        if (reasons.isNotEmpty()) return Readiness(null, reasons)

        return Readiness(
            parameters = Parameters(
                ecid = ecid!!,
                apChipId = chipId!!,
                apBoardId = boardId!!,
                apSecurityDomain = securityDomain!!,
                apProductionMode = true,
                apSecurityMode = true,
                apSupportsImg4 = true,
                apNonce = apNonce!!,
                apSepNonce = nonces.sepNonce,
                identityIndex = preflight.identityIndex,
                boardConfig = preflight.boardConfig,
                productVersion = preflight.productVersion,
                productBuildVersion = preflight.productBuildVersion
            ),
            reasons = emptyList()
        )
    }

    private fun parseHexTag(serial: String, tag: String): ULong? {
        if (serial.isBlank()) return null
        val match = Regex("(?:^|\\s)${Regex.escape(tag)}:([0-9A-Fa-f]+)(?=\\s|$)")
            .find(serial) ?: return null
        return match.groupValues[1].toULongOrNull(16)
    }
}
