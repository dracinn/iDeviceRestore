package io.github.dracinn.idevicerestore.download

import java.util.UUID

/**
 * User-requested IPSW transfer. Restore execution never consumes a partially
 * downloaded file; only [fileName] after the engine's atomic finalize step is
 * considered eligible for inspection.
 */
data class FirmwareDownloadRequest(
    val url: String,
    val fileName: String,
    val id: String = UUID.randomUUID().toString(),
    val expectedBytes: Long? = null,
    val expectedDigestAlgorithm: String? = null,
    val expectedDigestHex: String? = null,
    val allowMetered: Boolean = true,
) {
    init {
        require(url.startsWith("https://", ignoreCase = true)) { "Firmware downloads must use HTTPS" }
        require(fileName.endsWith(".ipsw", ignoreCase = true)) { "Firmware file must end in .ipsw" }
        require('/' !in fileName && '\\' !in fileName) { "Firmware file name must not contain a path" }
        require(expectedBytes == null || expectedBytes > 0L) { "Expected byte count must be positive" }
        require(
            (expectedDigestAlgorithm == null) == (expectedDigestHex == null)
        ) { "Digest algorithm and digest value must be provided together" }
        expectedDigestAlgorithm?.let {
            require(it.equals("SHA-256", true) || it.equals("SHA-1", true)) {
                "Only SHA-256 and SHA-1 firmware digests are supported"
            }
        }
    }
}

enum class FirmwareDownloadPhase {
    QUEUED,
    CONNECTING,
    DOWNLOADING,
    VERIFYING,
    COMPLETE,
    CANCELLED,
    FAILED,
}

data class FirmwareDownloadProgress(
    val requestId: String,
    val phase: FirmwareDownloadPhase,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val message: String? = null,
    val completedPath: String? = null,
    val computedDigestHex: String? = null,
)
