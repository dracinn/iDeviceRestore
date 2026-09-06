package com.idevicerestore.android

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-level adaptive downloader used by the foreground service.
 *
 * It preserves the existing FirmwareDownloader request/result/handle API, falls back to the current
 * single-stream implementation when range support is unavailable, and keeps final size/SHA checks
 * before the .part file is promoted to the finished IPSW.
 */
internal class AdaptiveFirmwareDownloader(
    private val logger: (String) -> Unit = {}
) {
    fun start(
        request: FirmwareDownloader.Request,
        onProgress: (FirmwareDownloader.Progress) -> Unit = {}
    ): FirmwareDownloader.DownloadHandle {
        val cancelled = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable {
            try {
                download(request, cancelled, onProgress)
            } finally {
                executor.shutdown()
            }
        })
        return FirmwareDownloader.DownloadHandle(cancelled, future)
    }

    private fun download(
        request: FirmwareDownloader.Request,
        cancelled: AtomicBoolean,
        onProgress: (FirmwareDownloader.Progress) -> Unit
    ): FirmwareDownloader.Result {
        require(request.url.startsWith("https://")) { "Only HTTPS downloads are allowed" }
        require(request.connections in 1..16) { "connections must be between 1 and 16" }

        val probe = probe(request)
        val total = when {
            request.expectedSize > 0L -> request.expectedSize
            probe.length > 0L -> probe.length
            else -> -1L
        }

        if (!probe.ranges || total <= 0L || request.connections <= 1) {
            discardUnsafeAdaptivePartialBeforeSequentialFallback(request, total)
            logger("FirmwareDownloader: adaptive mode unavailable; using current sequential downloader")
            return FirmwareDownloader(logger).download(
                request.copy(connections = 1),
                cancelled,
                onProgress
            )
        }

        if (request.expectedSize > 0L && probe.length > 0L && request.expectedSize != probe.length) {
            logger(
                "FirmwareDownloader: size metadata=${request.expectedSize}, server=${probe.length}; " +
                    "using metadata for final verification"
            )
        }

        logger(
            "FirmwareDownloader: adaptive mode enabled total=$total maxConnections=${request.connections} " +
                "destination=${request.destination.absolutePath}"
        )
        val transfer = AdaptiveRangeDownloader(logger).download(
            request = request,
            total = total,
            cancelled = cancelled,
            onProgress = onProgress
        )
        checkCancelled(cancelled)

        val part = transfer.file
        val actualSize = part.length()
        if (request.expectedSize > 0L && actualSize != request.expectedSize) {
            error("Firmware size mismatch: expected ${request.expectedSize}, got $actualSize")
        }
        if (actualSize != total) error("Firmware download incomplete: expected $total, got $actualSize")

        logger("FirmwareDownloader: verifying SHA-1 over $actualSize bytes")
        val sha1 = FirmwareIntegrity.sha1(part, cancelled)
        request.expectedSha1?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { expected ->
            if (sha1 != expected) {
                val removed = part.delete()
                File(request.destination.absolutePath + ".part.meta").delete()
                File(request.destination.absolutePath + ".part.meta.tmp").delete()
                logger("FirmwareDownloader: SHA-1 mismatch; invalid adaptive partial removed=$removed")
                error("SHA-1 mismatch: expected $expected, got $sha1")
            }
            logger("FirmwareDownloader: SHA-1 verified: $sha1")
        }

        if (request.destination.exists() && !request.destination.delete()) {
            error("Could not replace ${request.destination.absolutePath}")
        }
        // .part lives beside the final IPSW, so this should be an atomic same-filesystem rename and
        // does not require another full-size copy. Fail safely instead of falling back to copyTo.
        if (!part.renameTo(request.destination)) {
            error("Could not promote completed firmware without copying: ${request.destination.absolutePath}")
        }
        File(request.destination.absolutePath + ".part.meta").delete()
        File(request.destination.absolutePath + ".part.meta.tmp").delete()
        logger("FirmwareDownloader: adaptive transfer complete ${request.destination.absolutePath}")
        return FirmwareDownloader.Result(
            file = request.destination,
            bytes = request.destination.length(),
            sha1 = sha1,
            resumed = transfer.resumed,
            segmented = true
        )
    }

    private fun discardUnsafeAdaptivePartialBeforeSequentialFallback(
        request: FirmwareDownloader.Request,
        total: Long
    ) {
        val part = File(request.destination.absolutePath + ".part")
        val meta = File(request.destination.absolutePath + ".part.meta")
        val metaTemp = File(request.destination.absolutePath + ".part.meta.tmp")
        val hasAdaptiveMetadata = meta.exists() || metaTemp.exists()
        val ambiguousFullLengthPart = total > 0L && part.isFile && part.length() == total
        if (!hasAdaptiveMetadata && !ambiguousFullLengthPart) return

        // Adaptive downloads preallocate .part to the final length. The sequential downloader uses
        // length as proof of downloaded bytes, so an adaptive or otherwise ambiguous full-length
        // partial must never be handed to it. Prefer a safe restart over promoting sparse data.
        val removedPart = !part.exists() || part.delete()
        val removedMeta = !meta.exists() || meta.delete()
        val removedTemp = !metaTemp.exists() || metaTemp.delete()
        check(removedPart && removedMeta && removedTemp) {
            "Could not clear adaptive partial before sequential fallback"
        }
        logger("FirmwareDownloader: cleared adaptive/ambiguous partial before sequential fallback")
    }

    private data class Probe(val length: Long, val ranges: Boolean)

    private fun probe(request: FirmwareDownloader.Request): Probe {
        var connection: HttpURLConnection? = null
        return try {
            connection = open(request, "HEAD")
            val code = connection.responseCode
            if (code !in 200..399) error("Firmware probe HTTP $code")
            val length = connection.getHeaderFieldLong("Content-Length", -1L)
            val advertised = connection.getHeaderField("Accept-Ranges")
                ?.contains("bytes", ignoreCase = true) == true
            if (advertised) Probe(length, true) else rangeProbe(request, length)
        } catch (t: Throwable) {
            logger("FirmwareDownloader: adaptive HEAD probe failed: ${t.message}; trying range probe")
            rangeProbe(request, request.expectedSize)
        } finally {
            connection?.disconnect()
        }
    }

    private fun rangeProbe(request: FirmwareDownloader.Request, fallbackLength: Long): Probe {
        var connection: HttpURLConnection? = null
        return try {
            connection = open(request, "GET")
            connection.setRequestProperty("Range", "bytes=0-0")
            val code = connection.responseCode
            val contentRange = connection.getHeaderField("Content-Range").orEmpty()
            val length = contentRange.substringAfterLast('/', "").toLongOrNull()
                ?: connection.getHeaderFieldLong("Content-Length", fallbackLength)
            Probe(length, code == 206)
        } finally {
            runCatching { connection?.inputStream?.close() }
            connection?.disconnect()
        }
    }

    private fun open(request: FirmwareDownloader.Request, method: String): HttpURLConnection =
        (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = request.connectTimeoutMs
            readTimeout = request.readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "iDeviceRestore-Android/${BuildConfig.VERSION_NAME}")
        }

    private fun checkCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Download cancelled")
        }
    }
}
