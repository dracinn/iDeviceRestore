package com.idevicerestore.android

/** Central product-support gate for devices that iDeviceRestore must not operate on. */
object DeviceSupportPolicy {
    private val blockedIdentifierPrefixes = listOf("Mac15,", "Mac16,", "Mac17,")
    private val blockedChipName = Regex("(?i)(?:^|[\\s,(])M[345](?=$|[\\s,)])")

    /**
     * M3/M4/M5 Macs are intentionally unsupported. Identifier-family matching handles
     * catalog names that omit the chip generation; the name fallback covers future
     * catalog identifiers within these chip generations.
     */
    fun blockReason(device: FirmwareCatalog.Device): String? {
        if (!isMac(device)) return null

        val blockedByIdentifier = blockedIdentifierPrefixes.any { prefix ->
            device.identifier.startsWith(prefix, ignoreCase = true)
        }
        val blockedByChipName = blockedChipName.containsMatchIn(device.name)

        return if (blockedByIdentifier || blockedByChipName) {
            "M3, M4, and M5 Macs are intentionally unsupported by iDeviceRestore (${device.name}, ${device.identifier})"
        } else {
            null
        }
    }

    fun requireSupported(device: FirmwareCatalog.Device) {
        blockReason(device)?.let { reason -> throw UnsupportedOperationException(reason) }
    }

    fun requireSupportedIdentifier(identifier: String, devices: List<FirmwareCatalog.Device>) {
        val device = devices.firstOrNull { it.identifier.equals(identifier, ignoreCase = true) }
        if (device != null) {
            requireSupported(device)
            return
        }

        // Fail closed for known M3/M4/M5 Mac identifier families even if catalog lookup changes.
        if (blockedIdentifierPrefixes.any { prefix -> identifier.startsWith(prefix, ignoreCase = true) }) {
            throw UnsupportedOperationException(
                "M3, M4, and M5 Macs are intentionally unsupported by iDeviceRestore ($identifier)"
            )
        }
    }

    private fun isMac(device: FirmwareCatalog.Device): Boolean =
        device.identifier.startsWith("Mac", ignoreCase = true) ||
            device.name.contains("Mac", ignoreCase = true) ||
            device.name.contains("iMac", ignoreCase = true)
}
