package com.idevicerestore.android

/**
 * Central support classification for hardware that iDeviceRestore can identify.
 *
 * A device is only [SupportStatus.SUPPORTED] after a complete restore has been demonstrated with
 * the relevant iDeviceRestore restore path. Partial protocol success is represented as
 * [SupportStatus.EXPERIMENTAL], while intentionally excluded hardware is [SupportStatus.BLOCKED].
 */
object DeviceSupportMatrix {
    enum class SupportStatus {
        SUPPORTED,
        EXPERIMENTAL,
        BLOCKED,
        UNKNOWN
    }

    enum class Capability {
        IDENTIFICATION,
        RECOVERY_COMMUNICATION,
        DFU_COMMUNICATION,
        FIRMWARE_PREPARATION,
        FULL_RESTORE
    }

    data class Assessment(
        val status: SupportStatus,
        val capabilities: Set<Capability>,
        val summary: String
    ) {
        fun has(capability: Capability): Boolean = capability in capabilities
    }

    private val blockedMacIdentifierPrefixes = listOf("Mac15,", "Mac16,", "Mac17,")
    private val blockedChipName = Regex("(?i)(?:^|[\\s,(])M[345](?=$|[\\s,)])")

    // Apple silicon Macs for which upstream evidence demonstrates useful DFU/Recovery and
    // firmware-preparation behavior, but not sufficiently reliable end-to-end restore success.
    private val experimentalMacIdentifiers = listOf(
        Regex("(?i)^MacBookAir10,1$"),
        Regex("(?i)^MacBookPro17,1$"),
        Regex("(?i)^MacBookPro18,[1-4]$"),
        Regex("(?i)^Macmini9,1$"),
        Regex("(?i)^iMac21,[12]$"),
        Regex("(?i)^Mac13,[12]$"),
        Regex("(?i)^Mac14,\\d+$")
    )
    private val experimentalChipName = Regex("(?i)(?:^|[\\s,(])M[12](?=$|[\\s,)])")

    fun assess(device: FirmwareCatalog.Device): Assessment {
        if (!isMac(device)) {
            return Assessment(
                SupportStatus.UNKNOWN,
                setOf(Capability.IDENTIFICATION),
                "Detected device; restore support has not yet been classified by the iDeviceRestore matrix"
            )
        }

        if (isBlockedMac(device.identifier, device.name)) {
            return Assessment(
                SupportStatus.BLOCKED,
                setOf(Capability.IDENTIFICATION),
                "M3, M4, and M5 Macs are intentionally blocked from restore operations"
            )
        }

        if (isExperimentalAppleSiliconMac(device.identifier, device.name)) {
            return Assessment(
                SupportStatus.EXPERIMENTAL,
                setOf(
                    Capability.IDENTIFICATION,
                    Capability.RECOVERY_COMMUNICATION,
                    Capability.DFU_COMMUNICATION,
                    Capability.FIRMWARE_PREPARATION
                ),
                "M1 and M2 Mac restore support is experimental until an end-to-end restore is validated"
            )
        }

        return Assessment(
            SupportStatus.UNKNOWN,
            setOf(Capability.IDENTIFICATION),
            "Mac detected, but this model does not yet have an iDeviceRestore end-to-end support classification"
        )
    }

    fun assessIdentifier(identifier: String, devices: List<FirmwareCatalog.Device>): Assessment {
        devices.firstOrNull { it.identifier.equals(identifier, ignoreCase = true) }?.let(::assess)?.let {
            return it
        }

        if (blockedMacIdentifierPrefixes.any { identifier.startsWith(it, ignoreCase = true) }) {
            return Assessment(
                SupportStatus.BLOCKED,
                setOf(Capability.IDENTIFICATION),
                "M3, M4, and M5 Macs are intentionally blocked from restore operations"
            )
        }

        if (experimentalMacIdentifiers.any { it.matches(identifier) }) {
            return Assessment(
                SupportStatus.EXPERIMENTAL,
                setOf(
                    Capability.IDENTIFICATION,
                    Capability.RECOVERY_COMMUNICATION,
                    Capability.DFU_COMMUNICATION,
                    Capability.FIRMWARE_PREPARATION
                ),
                "M1 and M2 Mac restore support is experimental until an end-to-end restore is validated"
            )
        }

        return Assessment(
            SupportStatus.UNKNOWN,
            setOf(Capability.IDENTIFICATION),
            "Device identifier is not yet classified by the iDeviceRestore support matrix"
        )
    }

    private fun isBlockedMac(identifier: String, name: String): Boolean =
        blockedMacIdentifierPrefixes.any { identifier.startsWith(it, ignoreCase = true) } ||
            blockedChipName.containsMatchIn(name)

    private fun isExperimentalAppleSiliconMac(identifier: String, name: String): Boolean =
        experimentalMacIdentifiers.any { it.matches(identifier) } ||
            experimentalChipName.containsMatchIn(name)

    private fun isMac(device: FirmwareCatalog.Device): Boolean =
        device.identifier.startsWith("Mac", ignoreCase = true) ||
            device.name.contains("Mac", ignoreCase = true) ||
            device.name.contains("iMac", ignoreCase = true)
}
