package com.idevicerestore.android

import android.hardware.usb.UsbDevice

/**
 * Read-only extraction of Apple boot nonces from the USB serial descriptor.
 *
 * libirecovery populates ApNonce and SepNonce from the NONC/SNON tags exposed by iBoot when those
 * tags are present. Some boot stages/devices do not expose them in the descriptor; in that case this
 * class reports them as unavailable and callers must not invent or reuse stale nonce values.
 */
object DfuNonceInfo {
    data class Snapshot(
        val apNonce: ByteArray?,
        val sepNonce: ByteArray?,
        val source: String
    ) {
        val apNonceSize: Int get() = apNonce?.size ?: 0
        val sepNonceSize: Int get() = sepNonce?.size ?: 0
        val readyForApTss: Boolean get() = apNonceSize > 0
    }

    fun fromDevice(device: UsbDevice): Snapshot {
        val serial = runCatching { device.serialNumber }.getOrNull().orEmpty()
        return fromSerial(serial)
    }

    fun fromSerial(serial: String): Snapshot = Snapshot(
        apNonce = parseTag(serial, "NONC"),
        sepNonce = parseTag(serial, "SNON"),
        source = if (serial.isBlank()) "usb-serial-unavailable" else "usb-serial"
    )

    /** Privacy-safe status suitable for shared diagnostic logs; nonce bytes are never printed. */
    fun summary(device: UsbDevice): String {
        val snapshot = fromDevice(device)
        return "DFU nonce info: ApNonce=${snapshot.apNonceSize.takeIf { it > 0 }?.let { "$it bytes" } ?: "unavailable"} " +
            "ApSepNonce=${snapshot.sepNonceSize.takeIf { it > 0 }?.let { "$it bytes" } ?: "unavailable"} " +
            "source=${snapshot.source}"
    }

    private fun parseTag(serial: String, tag: String): ByteArray? {
        if (serial.isBlank()) return null
        // Match libirecovery's tag semantics: TAG:<hex> terminated by whitespace/end of buffer.
        val match = Regex("(?:^|\\s)${Regex.escape(tag)}:([0-9A-Fa-f]+)(?=\\s|$)")
            .find(serial) ?: return null
        val hex = match.groupValues[1]
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }
}
