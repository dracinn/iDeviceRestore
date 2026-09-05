package com.idevicerestore.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

object AppleUsb {
    const val APPLE_VID = 0x05AC

    private val dfuPids = setOf(0x1227)
    private val portDfuPids = setOf(0xF014)
    private val recoveryPids = setOf(0x1280, 0x1281, 0x1282, 0x1283)
    private val kisPids = setOf(0x1881)
    private val wtfPids = setOf(0x1222)
    private val bootIdRegex = Regex("\\b(CPID|CPRV|CPFM|SCEP|BDID|ECID|IBFL|PREV):([0-9A-Fa-f]+)\\b")

    enum class Mode { DFU, RECOVERY, WTF, APPLE_OTHER }

    enum class Personality {
        DFU,
        PORT_DFU,
        RECOVERY,
        KIS,
        WTF,
        APPLE_OTHER
    }

    data class BootIdentifiers(
        val rawSerial: String,
        val cpidHex: String?,
        val cprvHex: String?,
        val cpfmHex: String?,
        val scepHex: String?,
        val bdidHex: String?,
        val ecidHex: String?,
        val ibflHex: String?,
        val prevHex: String?
    ) {
        val cpid: Int? get() = cpidHex?.toIntOrNull(16)
        val bdid: Int? get() = bdidHex?.toIntOrNull(16)
        val prev: Int? get() = prevHex?.toIntOrNull(16)
        val image4Aware: Boolean?
            get() = ibflHex?.toLongOrNull(16)?.let { flags -> flags and IBOOT_FLAG_IMAGE4_AWARE != 0L }
    }

    fun personality(device: UsbDevice): Personality = when (device.productId) {
        in dfuPids -> Personality.DFU
        in portDfuPids -> Personality.PORT_DFU
        in recoveryPids -> Personality.RECOVERY
        in kisPids -> Personality.KIS
        in wtfPids -> Personality.WTF
        else -> Personality.APPLE_OTHER
    }

    /**
     * Existing operational modes intentionally exclude Port DFU and KIS. Those newer identities
     * are discovery-only until their transports are implemented and hardware-tested.
     */
    fun mode(device: UsbDevice): Mode = when (personality(device)) {
        Personality.DFU -> Mode.DFU
        Personality.RECOVERY -> Mode.RECOVERY
        Personality.WTF -> Mode.WTF
        Personality.PORT_DFU, Personality.KIS, Personality.APPLE_OTHER -> Mode.APPLE_OTHER
    }

    fun describe(device: UsbDevice): String = buildString {
        val personality = personality(device)
        append("VID=%04x PID=%04x".format(device.vendorId, device.productId))
        append(" mode=${mode(device)}")
        if (personality == Personality.PORT_DFU || personality == Personality.KIS) {
            append(" personality=$personality")
        }
        append(" interfaces=${device.interfaceCount}")
        runCatching { device.productName }.getOrNull()?.let { append(" product=$it") }
        runCatching { device.manufacturerName }.getOrNull()?.let { append(" manufacturer=$it") }
    }

    fun bootIdentifiers(device: UsbDevice): BootIdentifiers? {
        val serial = runCatching { device.serialNumber }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val values = bootIdRegex.findAll(serial)
            .associate { it.groupValues[1].uppercase() to it.groupValues[2].uppercase() }
        return BootIdentifiers(
            rawSerial = serial,
            cpidHex = values["CPID"],
            cprvHex = values["CPRV"],
            cpfmHex = values["CPFM"],
            scepHex = values["SCEP"],
            bdidHex = values["BDID"],
            ecidHex = values["ECID"],
            ibflHex = values["IBFL"],
            prevHex = values["PREV"]
        )
    }

    fun bootIdentifierSummary(device: UsbDevice): String {
        val identifiers = bootIdentifiers(device)
            ?: return if (runCatching { device.serialNumber }.getOrNull().isNullOrBlank()) {
                "USB serial descriptor: unavailable"
            } else {
                "USB serial descriptor: empty"
            }

        return buildString {
            append("USB serial descriptor: ").append(identifiers.rawSerial)
            val entries = listOf(
                "CPID" to identifiers.cpidHex,
                "CPRV" to identifiers.cprvHex,
                "CPFM" to identifiers.cpfmHex,
                "SCEP" to identifiers.scepHex,
                "BDID" to identifiers.bdidHex,
                "ECID" to identifiers.ecidHex,
                "IBFL" to identifiers.ibflHex,
                "PREV" to identifiers.prevHex
            ).filter { it.second != null }
            if (entries.isNotEmpty()) {
                append("\nBoot identifiers:")
                entries.forEach { (key, value) -> append(" $key=$value") }
            }
            identifiers.image4Aware?.let { append("\nImage4-aware: $it") }
        }
    }

    fun interfaceSummary(device: UsbDevice): String = buildString {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            append(
                "ifIndex=$i id=${intf.id} alt=${intf.alternateSetting} " +
                    "class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} " +
                    "protocol=${intf.interfaceProtocol} endpoints=${intf.endpointCount}\n"
            )
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "iso"
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
                    else -> ep.type.toString()
                }
                append("  ep=0x%02x $dir $type maxPacket=${ep.maxPacketSize}\n".format(ep.address))
            }
        }
    }

    data class Claimed(
        val intf: UsbInterface,
        val bulkIn: UsbEndpoint?,
        val bulkOut: UsbEndpoint?
    )

    private data class Candidate(
        val intf: UsbInterface,
        val bulkIn: UsbEndpoint?,
        val bulkOut: UsbEndpoint?,
        val score: Int
    )

    fun claimBestInterface(device: UsbDevice, connection: UsbDeviceConnection): Claimed? {
        RestorePreflightEvidenceStore.observeUsb(device)
        val candidates = mutableListOf<Candidate>()
        val personality = personality(device)
        if (personality != Personality.RECOVERY) RestorePreflightEvidenceStore.clearRecovery()

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null

            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (ep.direction) {
                    UsbConstants.USB_DIR_IN -> if (bulkIn == null) bulkIn = ep
                    UsbConstants.USB_DIR_OUT -> if (bulkOut == null) bulkOut = ep
                }
            }

            val score = when (personality) {
                Personality.RECOVERY -> {
                    var s = 0
                    if (intf.id == 0) s += 500
                    if (intf.alternateSetting == 0) s += 100
                    if (bulkIn != null) s += 20
                    if (bulkOut != null) s += 10
                    s
                }
                Personality.WTF, Personality.DFU, Personality.PORT_DFU -> {
                    var s = 0
                    if (intf.id == 0) s += 200
                    if (intf.alternateSetting == 0) s += 100
                    if (intf.interfaceClass == 254) s += 100
                    if (intf.interfaceSubclass == 1) s += 50
                    if (intf.endpointCount == 0) s += 10
                    s
                }
                Personality.KIS, Personality.APPLE_OTHER -> {
                    var s = 0
                    if (intf.id == 0) s += 100
                    if (intf.alternateSetting == 0) s += 50
                    if (bulkIn != null) s += 20
                    if (bulkOut != null) s += 10
                    s
                }
            }

            candidates += Candidate(intf, bulkIn, bulkOut, score)
        }

        for (candidate in candidates.sortedByDescending { it.score }) {
            if (connection.claimInterface(candidate.intf, true)) {
                return Claimed(candidate.intf, candidate.bulkIn, candidate.bulkOut)
            }
        }

        return null
    }

    /**
     * Claims a secondary Recovery alternate setting that exposes a bulk-IN endpoint.
     * The primary iBoot command/control interface remains interface 0/alt 0.
     * This helper is diagnostic/read-only and is not used for firmware upload.
     */
    fun claimRecoveryConsoleInterface(device: UsbDevice, connection: UsbDeviceConnection): Claimed? {
        if (mode(device) != Mode.RECOVERY) return null

        val candidates = mutableListOf<Candidate>()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.id == 0) continue
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (ep.direction) {
                    UsbConstants.USB_DIR_IN -> if (bulkIn == null) bulkIn = ep
                    UsbConstants.USB_DIR_OUT -> if (bulkOut == null) bulkOut = ep
                }
            }
            if (bulkIn == null) continue
            var score = 100
            if (intf.alternateSetting > 0) score += 50
            if (bulkOut != null) score += 10
            candidates += Candidate(intf, bulkIn, bulkOut, score)
        }

        for (candidate in candidates.sortedByDescending { it.score }) {
            if (!connection.claimInterface(candidate.intf, true)) continue
            if (candidate.intf.alternateSetting != 0 && !connection.setInterface(candidate.intf)) {
                connection.releaseInterface(candidate.intf)
                continue
            }
            return Claimed(candidate.intf, candidate.bulkIn, candidate.bulkOut)
        }
        return null
    }

    private const val IBOOT_FLAG_IMAGE4_AWARE = 1L shl 2
}
