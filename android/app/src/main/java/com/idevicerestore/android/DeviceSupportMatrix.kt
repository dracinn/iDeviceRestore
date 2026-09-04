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

    // M1/M2 families remain experimental as a restore-support status, but this list must not be
    // treated as evidence that every model has passed each individual protocol stage.
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

    // Only identifiers with explicit restore-stage evidence belong here. Add capabilities narrowly
    // as hardware or upstream logs demonstrate them; do not infer them from chip generation alone.
    private val evidencedCapabilitiesByIdentifier = mapOf(
        "macbookair10,1" to setOf(
            Capability.IDENTIFICATION,
            Capability.RECOVERY_COMMUNICATION,
            Capability.DFU_COMMUNICATION,
            Capability.FIRMWARE_PREPARATION
        ),
        "macbookpro17,1" to setOf(
            Capability.IDENTIFICATION,
            Capability.RECOVERY_COMMUNICATION,
            Capability.DFU_COMMUNICATION,
            Capability.FIRMWARE_PREPARATION
        ),
        "mac14,2" to setOf(
            Capability.IDENTIFICATION,
            Capability.RECOVERY_COMMUNICATION,
            Capability.DFU_COMMUNICATION,
            Capability.FIRMWARE_PREPARATION
        )
    )

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
            return experimentalAssessment(device.identifier)
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
            return experimentalAssessment(identifier)
        }

        return Assessment(
            SupportStatus.UNKNOWN,
            setOf(Capability.IDENTIFICATION),
            "Device identifier is not yet classified by the iDeviceRestore support matrix"
        )
    }

    private fun experimentalAssessment(identifier: String): Assessment {
        val capabilities = evidencedCapabilitiesByIdentifier[identifier.lowercase()]
            ?: setOf(Capability.IDENTIFICATION)
        val hasProtocolEvidence = capabilities.size > 1
        return Assessment(
            SupportStatus.EXPERIMENTAL,
            capabilities,
            if (hasProtocolEvidence) {
                "M1/M2 Mac restore support is experimental; this model has evidence for partial restore stages but not a validated end-to-end restore"
            } else {
                "M1/M2 Mac restore support is experimental; protocol-stage capabilities are not yet evidenced for this model"
            }
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
