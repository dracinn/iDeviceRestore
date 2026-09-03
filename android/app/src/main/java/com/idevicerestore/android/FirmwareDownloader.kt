package com.idevicerestore.android

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Resumable HTTP(S) firmware downloader inspired by aria2's transfer behavior:
 * ranged requests, multiple connections, retries, progress, cancellation and checksum verification.
 *
 * This is deliberately a Kotlin framework rather than a bundled aria2 binary so it can share
 * Android storage and lifecycle policy with the restore app. It only downloads normal HTTPS URLs.
 */
class FirmwareDownloader(
    private val logger: (String) -> Unit = {}
) {
    data class Request(
        val url: String,
        val destination: File,
        val expectedSize: Long = -1L,
        val expectedSha1: String? = null,
        val connections: Int = 4,
        val maxRetries: Int = 5,
        val connectTimeoutMs: Int = 15_000,
        val readTimeoutMs: Int = 30_000
    )

    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val activeConnections: Int
    ) {
        val fraction: Double
            get() = if (totalBytes > 0) downloadedBytes.toDouble() / totalBytes else 0.0
    }

    data class Result(
        val file: File,
        val bytes: Long,
        val sha1: String,
        val resumed: Boolean,
        val segmented: Boolean
    )

    class DownloadHandle internal constructor(
        private val cancelled: AtomicBoolean,
        private val future: Future<Result>
    ) {
        fun cancel() {
            cancelled.set(true)
            future.cancel(true)
        }

        fun isCancelled(): Boolean = cancelled.get()
        fun await(): Result = future.get()
    }

    private data class Probe(val length: Long, val ranges: Boolean)

    fun start(request: Request, onProgress: (Progress) -> Unit = {}): DownloadHandle {
        require(request.url.startsWith("https://")) { "Only HTTPS downloads are allowed" }
        require(request.connections in 1..16) { "connections must be between 1 and 16" }
        require(request.maxRetries >= 0) { "maxRetries must be >= 0" }

        val cancelled = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable {
            try {
                download(request, cancelled, onProgress)
            } finally {
                executor.shutdown()
            }
        })
        return DownloadHandle(cancelled, future)
    }

    fun download(
        request: Request,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (Progress) -> Unit = {}
    ): Result {
        request.destination.parentFile?.mkdirs()
        val probe = probe(request)
        val total = when {
            request.expectedSize > 0 -> request.expectedSize
            probe.length > 0 -> probe.length
            else -> -1L
        }
        if (request.expectedSize > 0 && probe.length > 0 && request.expectedSize != probe.length) {
            logger("FirmwareDownloader: size metadata=${request.expectedSize}, server=${probe.length}; using metadata for verification")
        }

        val canSegment = probe.ranges && total > 0 && request.connections > 1
        logger("FirmwareDownloader: url=${request.url}")
        logger("FirmwareDownloader: destination=${request.destination.absolutePath}")
        logger("FirmwareDownloader: total=$total rangeSupport=${probe.ranges} connections=${if (canSegment) request.connections else 1}")

        val result = if (canSegment) {
            segmentedDownload(request, total, cancelled, onProgress)
        } else {
            sequentialDownload(request, total, cancelled, onProgress)
        }
        if (cancelled.get()) throw InterruptedException("Download cancelled")

        val actualSize = result.first.length()
        if (request.expectedSize > 0 && actualSize != request.expectedSize) {
            error("Firmware size mismatch: expected ${request.expectedSize}, got $actualSize")
        }
        if (total > 0 && actualSize != total) {
            error("Firmware download incomplete: expected $total, got $actualSize")
        }

        logger("FirmwareDownloader: verifying SHA-1 over $actualSize bytes")
        val sha1 = sha1(result.first, cancelled)
        request.expectedSha1?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { expected ->
            if (sha1 != expected) error("SHA-1 mismatch: expected $expected, got $sha1")
            logger("FirmwareDownloader: SHA-1 verified: $sha1")
        }

        if (request.destination.exists() && !request.destination.delete()) {
            error("Could not replace ${request.destination.absolutePath}")
        }
        if (!result.first.renameTo(request.destination)) {
            result.first.copyTo(request.destination, overwrite = true)
            result.first.delete()
        }
        cleanupSegments(request.destination)
        logger("FirmwareDownloader: complete ${request.destination.absolutePath}")
        return Result(request.destination, request.destination.length(), sha1, result.second, canSegment)
    }

    private fun sequentialDownload(
        request: Request,
        total: Long,
        cancelled: AtomicBoolean,
        onProgress: (Progress) -> Unit
    ): Pair<File, Boolean> {
        val part = File(request.destination.absolutePath + ".part")
        var offset = part.takeIf { it.exists() }?.length() ?: 0L
        val resumed = offset > 0
        if (total > 0 && offset > total) {
            part.delete()
            offset = 0
        }
        val downloaded = AtomicLong(offset)
        val startedAt = System.nanoTime()
        logger("FirmwareDownloader: sequential start offset=$offset")

        retry(request.maxRetries, cancelled) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                connection = open(request.url, request, "GET")
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val code = connection.responseCode
                if (code !in listOf(200, 206)) error("HTTP $code ${connection.responseMessage}")
                if (offset > 0 && code == 200) {
                    logger("FirmwareDownloader: server ignored resume range; restarting")
                    part.delete()
                    offset = 0
                    downloaded.set(0)
                }
                FileOutputStream(part, offset > 0).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        while (true) {
                            checkCancelled(cancelled)
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            val now = downloaded.addAndGet(count.toLong())
                            emitProgress(now, total, startedAt, 1, onProgress)
                        }
                        output.fd.sync()
                    }
                }
                logger("FirmwareDownloader: sequential transfer finished on attempt ${attempt + 1}")
                return@retry
            } finally {
                connection?.disconnect()
            }
        }
        return part to resumed
    }

    private fun segmentedDownload(
        request: Request,
        total: Long,
        cancelled: AtomicBoolean,
        onProgress: (Progress) -> Unit
    ): Pair<File, Boolean> {
        val count = min(request.connections, maxOf(1, (total / (4L * 1024 * 1024)).toInt()))
        val chunk = (total + count - 1) / count
        val parts = (0 until count).map { index -> File(request.destination.absolutePath + ".part.$index") }
        val resumed = parts.any { it.exists() && it.length() > 0 }
        val initialBytes = parts.sumOf { it.takeIf(File::exists)?.length() ?: 0L }
        val downloaded = AtomicLong(initialBytes)
        val startedAt = System.nanoTime()
        val pool = Executors.newFixedThreadPool(count)
        logger("FirmwareDownloader: segmented transfer count=$count chunk=$chunk resumed=$resumed")

        try {
            val futures = mutableListOf<Future<*>>()
            for (index in 0 until count) {
                val start = index * chunk
                val end = min(total - 1, start + chunk - 1)
                val part = parts[index]
                if (part.length() > end - start + 1) part.delete()
                futures += pool.submit {
                    val existing = part.takeIf(File::exists)?.length() ?: 0L
                    if (start + existing > end) return@submit
                    retry(request.maxRetries, cancelled) { attempt ->
                        var connection: HttpURLConnection? = null
                        try {
                            val rangeStart = start + part.length()
                            if (rangeStart > end) return@retry
                            connection = open(request.url, request, "GET")
                            connection.setRequestProperty("Range", "bytes=$rangeStart-$end")
                            val code = connection.responseCode
                            if (code != 206) error("Segment $index expected HTTP 206, got $code")
                            logger("FirmwareDownloader: segment=$index range=$rangeStart-$end attempt=${attempt + 1}")
                            FileOutputStream(part, true).use { output ->
                                connection.inputStream.use { input ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                                    while (true) {
                                        checkCancelled(cancelled)
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        output.write(buffer, 0, read)
                                        val now = downloaded.addAndGet(read.toLong())
                                        emitProgress(now, total, startedAt, count, onProgress)
                                    }
                                    output.fd.sync()
                                }
                            }
                            val expected = end - start + 1
                            if (part.length() != expected) error("Segment $index incomplete: ${part.length()}/$expected")
                            return@retry
                        } finally {
                            connection?.disconnect()
                        }
                    }
                }
            }
            futures.forEach { it.get() }
        } catch (t: Throwable) {
            cancelled.set(cancelled.get() || t is InterruptedException)
            throw t
        } finally {
            pool.shutdownNow()
        }

        val assembled = File(request.destination.absolutePath + ".part")
        if (assembled.exists()) assembled.delete()
        FileOutputStream(assembled).use { output ->
            parts.forEachIndexed { index, part ->
                checkCancelled(cancelled)
                logger("FirmwareDownloader: assembling segment=$index bytes=${part.length()}")
                FileInputStream(part).use { it.copyTo(output, DEFAULT_BUFFER_SIZE * 16) }
            }
            output.fd.sync()
        }
        parts.forEach { it.delete() }
        return assembled to resumed
    }

    private fun probe(request: Request): Probe {
        var connection: HttpURLConnection? = null
        return try {
            connection = open(request.url, request, "HEAD")
            val code = connection.responseCode
            if (code !in 200..399) error("Firmware probe HTTP $code")
            val length = connection.getHeaderFieldLong("Content-Length", -1L)
            val acceptRanges = connection.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            if (acceptRanges) Probe(length, true) else rangeProbe(request, length)
        } catch (t: Throwable) {
            logger("FirmwareDownloader: HEAD probe failed: ${t.message}; trying range probe")
            rangeProbe(request, request.expectedSize)
        } finally {
            connection?.disconnect()
        }
    }

    private fun rangeProbe(request: Request, fallbackLength: Long): Probe {
        var connection: HttpURLConnection? = null
        return try {
            connection = open(request.url, request, "GET")
            connection.setRequestProperty("Range", "bytes=0-0")
            val code = connection.responseCode
            val contentRange = connection.getHeaderField("Content-Range").orEmpty()
            val length = contentRange.substringAfterLast('/', "").toLongOrNull()
                ?: connection.getHeaderFieldLong("Content-Length", fallbackLength)
            Probe(length, code == 206)
        } finally {
            connection?.inputStream?.close()
            connection?.disconnect()
        }
    }

    private fun open(url: String, request: Request, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = request.connectTimeoutMs
            readTimeout = request.readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "iDeviceRestore-Android/${BuildConfig.VERSION_NAME}")
        }

    private fun retry(maxRetries: Int, cancelled: AtomicBoolean, block: (Int) -> Unit) {
        var last: Throwable? = null
        for (attempt in 0..maxRetries) {
            checkCancelled(cancelled)
            try {
                block(attempt)
                return
            } catch (t: Throwable) {
                if (t is InterruptedException || cancelled.get()) throw t
                last = t
                if (attempt >= maxRetries) break
                val delayMs = min(30_000L, 1_000L shl min(attempt, 5))
                logger("FirmwareDownloader: attempt ${attempt + 1} failed: ${t.javaClass.simpleName}: ${t.message}; retry in ${delayMs}ms")
                var remaining = delayMs
                while (remaining > 0) {
                    checkCancelled(cancelled)
                    val slice = min(remaining, 250L)
                    Thread.sleep(slice)
                    remaining -= slice
                }
            }
        }
        throw last ?: IllegalStateException("Download failed")
    }

    private fun emitProgress(
        bytes: Long,
        total: Long,
        startedAt: Long,
        active: Int,
        callback: (Progress) -> Unit
    ) {
        val elapsedSeconds = (System.nanoTime() - startedAt).coerceAtLeast(1L) / 1_000_000_000.0
        callback(Progress(bytes, total, (bytes / elapsedSeconds).toLong(), active))
    }

    private fun sha1(file: File, cancelled: AtomicBoolean): String {
        val digest = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
            while (true) {
                checkCancelled(cancelled)
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cleanupSegments(destination: File) {
        destination.parentFile?.listFiles()?.filter {
            it.name.startsWith(destination.name + ".part.")
        }?.forEach { it.delete() }
    }

    private fun checkCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException("Download cancelled")
    }
}
