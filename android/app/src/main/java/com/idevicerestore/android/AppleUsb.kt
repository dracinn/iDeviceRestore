package com.idevicerestore.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

object AppleUsb {
    const val APPLE_VID = 0x05AC

    private val dfuPids = setOf(0x1227)
    private val recoveryPids = setOf(0x1280, 0x1281)
    private val wtfPids = setOf(0x1222)

    enum class Mode { DFU, RECOVERY, WTF, APPLE_OTHER }

    fun mode(device: UsbDevice): Mode = when (device.productId) {
        in dfuPids -> Mode.DFU
        in recoveryPids -> Mode.RECOVERY
        in wtfPids -> Mode.WTF
        else -> Mode.APPLE_OTHER
    }

    fun describe(device: UsbDevice): String = buildString {
        append("VID=%04x PID=%04x".format(device.vendorId, device.productId))
        append(" mode=${mode(device)}")
        append(" interfaces=${device.interfaceCount}")
        runCatching { device.productName }.getOrNull()?.let { append(" product=$it") }
        runCatching { device.manufacturerName }.getOrNull()?.let { append(" manufacturer=$it") }
    }

    fun interfaceSummary(device: UsbDevice): String = buildString {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            append("if#$i class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} protocol=${intf.interfaceProtocol} endpoints=${intf.endpointCount}\n")
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

    data class Claimed(val intf: UsbInterface, val bulkIn: UsbEndpoint?)

    fun claimBestInterface(device: UsbDevice, connection: UsbDeviceConnection): Claimed? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (!connection.claimInterface(intf, true)) continue
            var bulkIn: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) {
                    bulkIn = ep
                    break
                }
            }
            return Claimed(intf, bulkIn)
        }
        return null
    }
}
