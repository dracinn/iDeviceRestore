package com.idevicerestore.android

import java.time.Instant

data class FirmwareSelectionCandidate(
    val channel: Channel,
    val version: String,
    val buildId: String,
    val releaseDate: Instant?,
    val fileSize: Long,
    val stableFirmware: FirmwareCatalog.Firmware? = null,
    val betaCandidate: BetaFirmwareCatalog.Candidate? = null
) {
    enum class Channel { STABLE, BETA, RC }

    val label: String
        get() {
            val channelLabel = when (channel) {
                Channel.STABLE -> "Stable"
                Channel.BETA -> "Beta"
                Channel.RC -> "RC"
            }
            val sizeLabel = if (fileSize > 0L) FirmwareDownloadService.formatBytes(fileSize) else "size unknown"
            return "[$channelLabel] $version ($buildId) — $sizeLabel"
        }
}
