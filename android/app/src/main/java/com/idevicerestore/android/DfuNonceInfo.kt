package com.idevicerestore.android

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.io.IOException
import java.nio.charset.Charset

/**
 * Read-only extraction of Apple boot nonces.
 *
 * libirecovery explicitly reads USB string descriptor index 1 and parses NONC/SNON from that
 * buffer. Android's UsbDevice.serialNumber is a different cached property and can omit those tags,
 * so restore code should prefer [fromConnection] while the DFU device is open.
 */
object DfuNonceInfo {
    data class Snapshot(
        val apNonce: ByteArray?,
        val sepNonce: ByteArray?,
        val source: String,
        val descriptorTextPresent: Boolean = false
    ) {
        val apNonceSize: Int get() = apNonce?.size ?: 0
        val sepNonceSize: Int get() = sepNonce?.size ?: 0
        val readyForApTss: Boolean get() = apNonceSize > 0

        /** Privacy-safe summary; descriptor contents and nonce bytes are intentionally omitted. */
        fun summary(): String =
            "DFU nonce info: ApNonce=${apNonceSize.takeIf { it > 0 }?.let { "$it bytes" } ?: "unavailable"} " +
                "ApSepNonce=${sepNonceSize.takeIf { it > 0 }?.let { "$it bytes" } ?: "unavailable"} " +
                "source=$source descriptorText=${if (descriptorTextPresent) "present" else "unavailable"}"
    }

    /**
     * Mirrors libirecovery's nonce source: USB string descriptor index 1.
     * All transfers are standard device-to-host GET_DESCRIPTOR requests and are read-only.
     */
    fun fromConnection(connection: UsbDeviceConnection): Snapshot {
        val text = readStringDescriptorAscii(connection, NONCE_STRING_DESCRIPTOR_INDEX)
        return Snapshot(
            apNonce = parseTag(text, "NONC"),
            sepNonce = parseTag(text, "SNON"),
            source = "usb-string-descriptor-1",
            descriptorTextPresent = text.isNotBlank()
        )
    }

    /** Fallback for diagnostics when an open UsbDeviceConnection is not available. */
    fun fromDevice(device: UsbDevice): Snapshot {
        val serial = runCatching { device.serialNumber }.getOrNull().orEmpty()
        return fromSerial(serial)
    }

    fun fromSerial(serial: String): Snapshot = Snapshot(
        apNonce = parseTag(serial, "NONC"),
        sepNonce = parseTag(serial, "SNON"),
        source = if (serial.isBlank()) "usb-serial-unavailable" else "usb-serial",
        descriptorTextPresent = serial.isNotBlank()
    )

    fun summary(device: UsbDevice): String = fromDevice(device).summary()

    private fun readStringDescriptorAscii(connection: UsbDeviceConnection, descriptorIndex: Int): String {
        require(descriptorIndex in 1..255) { "USB string descriptor index must be between 1 and 255" }

        val languageDescriptor = ByteArray(255)
        val languageLength = connection.controlTransfer(
            USB_REQUEST_TYPE_STANDARD_IN,
            USB_REQUEST_GET_DESCRIPTOR,
            USB_DESCRIPTOR_TYPE_STRING shl 8,
            0,
            languageDescriptor,
            languageDescriptor.size,
            USB_TIMEOUT_MS
        )
        val languageId = if (
            languageLength >= 4 &&
            (languageDescriptor[1].toInt() and 0xFF) == USB_DESCRIPTOR_TYPE_STRING
        ) {
            (languageDescriptor[2].toInt() and 0xFF) or
                ((languageDescriptor[3].toInt() and 0xFF) shl 8)
        } else {
            // libusb_get_string_descriptor_ascii() resolves a language internally. English-US is
            // a safe standards-compatible fallback if descriptor zero is unavailable on Android.
            DEFAULT_LANGUAGE_ID
        }

        val data = ByteArray(255)
        val received = connection.controlTransfer(
            USB_REQUEST_TYPE_STANDARD_IN,
            USB_REQUEST_GET_DESCRIPTOR,
            (USB_DESCRIPTOR_TYPE_STRING shl 8) or descriptorIndex,
            languageId,
            data,
            data.size,
            USB_TIMEOUT_MS
        )
        if (received < 0) throw IOException("USB string descriptor $descriptorIndex read failed: $received")
        if (received < 2) return ""
        if ((data[1].toInt() and 0xFF) != USB_DESCRIPTOR_TYPE_STRING) {
            throw IOException("USB descriptor $descriptorIndex is not a string descriptor")
        }

        val declaredLength = data[0].toInt() and 0xFF
        val actualLength = minOf(received, declaredLength.takeIf { it >= 2 } ?: received)
        val payloadLength = (actualLength - 2).coerceAtLeast(0) and -2
        if (payloadLength == 0) return ""

        return String(data, 2, payloadLength, UTF16_LE)
            .trimEnd('\u0000')
    }

    private fun parseTag(buffer: String, tag: String): ByteArray? {
        if (buffer.isBlank()) return null
        // Match libirecovery's TAG:<hex> semantics while tolerating punctuation after the value.
        val match = Regex("(?:^|\\s)${Regex.escape(tag)}:([0-9A-Fa-f]+)")
            .find(buffer) ?: return null
        val hex = match.groupValues[1]
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    private val UTF16_LE: Charset = Charset.forName("UTF-16LE")

    private const val NONCE_STRING_DESCRIPTOR_INDEX = 1
    private const val USB_REQUEST_TYPE_STANDARD_IN = 0x80
    private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
    private const val USB_DESCRIPTOR_TYPE_STRING = 0x03
    private const val DEFAULT_LANGUAGE_ID = 0x0409
    private const val USB_TIMEOUT_MS = 10_000
}
