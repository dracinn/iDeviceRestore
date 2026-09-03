package com.idevicerestore.android

import java.time.Instant

/**
 * One firmware choice returned from a catalog that was queried for the exact connected
 * device identifier. The chooser deliberately sorts by macOS version rather than release date.
 */
class FirmwareSelectionCandidate(
    val channel: Channel,
    val version: String,
    val buildId: String,
    releaseDate: Instant?,
    val fileSize: Long,
    val stableFirmware: FirmwareCatalog.Firmware? = null,
    val betaCandidate: BetaFirmwareCatalog.Candidate? = null
) {
    enum class Channel { STABLE, BETA, RC }

    /** Original catalog timestamp retained for diagnostics; it is not used for chooser ordering. */
    val catalogReleaseDate: Instant? = releaseDate

    private val versionMatch = Regex("^\\s*([0-9]+)(?:\\.([0-9]+))?(?:\\.([0-9]+))?(?:\\.([0-9]+))?")
        .find(version)

    val macOsMajor: Int = versionMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
    private val macOsMinor: Int = versionMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
    private val macOsPatch: Int = versionMatch?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 0
    private val macOsRevision: Int = versionMatch?.groupValues?.getOrNull(4)?.toIntOrNull() ?: 0
    private val prereleaseNumber: Int = Regex("(?i)\\b(?:beta|RC)\\s+([0-9]+)")
        .find(version)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: 0

    private val channelRank: Int = when (channel) {
        Channel.STABLE -> 3
        Channel.RC -> 2
        Channel.BETA -> 1
    }

    /**
     * MainActivity already sorts by releaseDate. Supply a synthetic ordering value derived only
     * from macOS version/channel so the UI groups macOS families together without a release-date
     * migration across the rest of the chooser code.
     */
    val releaseDate: Instant = Instant.ofEpochSecond(
        (macOsMajor.coerceAtLeast(0).toLong() * 1_000_000_000L) +
            (macOsMinor.toLong() * 1_000_000L) +
            (macOsPatch.toLong() * 10_000L) +
            (macOsRevision.toLong() * 100L) +
            (channelRank.toLong() * 10L) +
            prereleaseNumber.coerceIn(0, 9).toLong()
    )

    val macOsLabel: String
        get() = if (macOsMajor >= 0) "macOS $macOsMajor" else "macOS version unknown"

    val label: String
        get() {
            val channelLabel = when (channel) {
                Channel.STABLE -> "Stable"
                Channel.BETA -> "Beta"
                Channel.RC -> "RC"
            }
            val sizeLabel = if (fileSize > 0L) FirmwareDownloadService.formatBytes(fileSize) else "size unknown"
            return "[$macOsLabel • $channelLabel] $version ($buildId) — $sizeLabel"
        }
}
