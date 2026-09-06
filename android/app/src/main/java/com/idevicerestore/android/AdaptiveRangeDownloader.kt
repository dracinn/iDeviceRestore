package com.idevicerestore.android

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.min

/**
 * Multipart range downloader that writes directly into one preallocated .part file.
 *
 * A tiny .part.meta bitmap records completed chunks, so the physical payload is never duplicated
 * during assembly and interrupted downloads can resume only the missing ranges. Worker threads are
 * kept in a fixed pool while [targetConnections] is adjusted at runtime; workers above the target
 * simply stop claiming new chunks until capacity is useful again.
 */
internal class AdaptiveRangeDownloader(
    private val logger: (String) -> Unit = {}
) {
    data class Result(val file: File, val resumed: Boolean)

    fun download(
        request: FirmwareDownloader.Request,
        total: Long,
        cancelled: AtomicBoolean,
        onProgress: (FirmwareDownloader.Progress) -> Unit
    ): Result {
        require(total > 0) { "Adaptive range download requires a known payload size" }

        val destination = request.destination
        val part = File(destination.absolutePath + ".part")
        val meta = File(destination.absolutePath + ".part.meta")
        val chunkSize = chooseChunkSize(total)
        val chunkCount = ceil(total.toDouble() / chunkSize.toDouble()).toInt().coerceAtLeast(1)
        val maxConnections = min(request.connections.coerceAtLeast(1), chunkCount)
        val initialConnections = min(2, maxConnections).coerceAtLeast(1)
        val lock = Any()
        val completed = BooleanArray(chunkCount)
        val inFlight = BooleanArray(chunkCount)
        val originalPartLength = part.takeIf(File::exists)?.length() ?: 0L
        var resumed = false

        val loaded = loadMeta(meta, request.url, total, chunkSize, completed)
        if (loaded && part.exists()) {
            resumed = completed.any { it }
            logger("FirmwareDownloader: adaptive resume metadata loaded; completed=${completed.count { it }}/$chunkCount")
        } else {
            if (meta.exists()) meta.delete()
            if (part.exists() && originalPartLength > 0L && originalPartLength <= total) {
                // Previous single-stream downloads are contiguous from byte zero. Preserve every
                // fully completed chunk and redownload at most one trailing partial chunk.
                for (index in completed.indices) {
                    if (chunkEnd(index, chunkSize, total) + 1L <= originalPartLength) completed[index] = true
                }
                resumed = completed.any { it }
                if (resumed) {
                    logger(
                        "FirmwareDownloader: imported sequential .part resume state bytes=$originalPartLength " +
                            "completed=${completed.count { it }}/$chunkCount"
                    )
                }
            } else if (part.exists() && originalPartLength > total) {
                part.delete()
            }
        }

        part.parentFile?.mkdirs()
        RandomAccessFile(part, "rw").use { file -> file.setLength(total) }
        persistMeta(meta, request.url, total, chunkSize, completed)

        val completedBytes = AtomicLong(completed.indices.sumOf { index ->
            if (completed[index]) chunkLength(index, chunkSize, total) else 0L
        })
        val targetConnections = AtomicInteger(initialConnections)
        val activeConnections = AtomicInteger(0)
        val failuresSinceSample = AtomicInteger(0)
        val finished = AtomicBoolean(completed.all { it })
        val startedAt = System.nanoTime()
        val pool = Executors.newFixedThreadPool(maxConnections)

        logger(
            "FirmwareDownloader: adaptive ranged transfer chunks=$chunkCount chunkSize=$chunkSize " +
                "connections=$initialConnections..$maxConnections resumed=$resumed"
        )
        emitProgress(completedBytes.get(), total, startedAt, activeConnections.get(), onProgress)

        val controller = Thread {
            var previousBytes = completedBytes.get()
            var previousRate = 0L
            var lastAdjustmentMs = 0L
            while (!cancelled.get() && !finished.get()) {
                try {
                    Thread.sleep(CONTROL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (cancelled.get() || finished.get()) break

                val nowBytes = completedBytes.get()
                val rate = ((nowBytes - previousBytes).coerceAtLeast(0L) * 1000L) / CONTROL_INTERVAL_MS
                previousBytes = nowBytes
                val failures = failuresSinceSample.getAndSet(0)
                val current = targetConnections.get()
                val nowMs = System.currentTimeMillis()
                var next = current

                if (failures >= 2 && current > 1) {
                    next = current - 1
                } else if (
                    previousRate > 0L &&
                    rate < (previousRate * 65L) / 100L &&
                    current > 1 &&
                    nowMs - lastAdjustmentMs >= ADJUSTMENT_COOLDOWN_MS
                ) {
                    next = current - 1
                } else if (
                    failures == 0 &&
                    current < maxConnections &&
                    rate >= MIN_SCALE_UP_BPS &&
                    nowMs - lastAdjustmentMs >= ADJUSTMENT_COOLDOWN_MS
                ) {
                    // Hill-climb conservatively. If the previous increase hurt throughput badly,
                    // the branch above backs off; otherwise add one connection at a time.
                    next = current + 1
                }

                if (next != current) {
                    targetConnections.set(next)
                    lastAdjustmentMs = nowMs
                    logger(
                        "FirmwareDownloader: adaptive connections $current->$next " +
                            "rate=$rate B/s failures=$failures"
                    )
                }
                previousRate = rate
            }
        }.apply {
            name = "FirmwareAdaptiveController"
            isDaemon = true
            start()
        }

        try {
            val futures = (0 until maxConnections).map { workerId ->
                pool.submit {
                    while (!cancelled.get()) {
                        if (workerId >= targetConnections.get()) {
                            if (finished.get()) return@submit
                            Thread.sleep(IDLE_SLEEP_MS)
                            continue
                        }

                        val index = synchronized(lock) {
                            val candidate = completed.indices.firstOrNull { !completed[it] && !inFlight[it] }
                            if (candidate != null) inFlight[candidate] = true
                            candidate
                        }

                        if (index == null) {
                            val allDone = synchronized(lock) { completed.all { it } }
                            if (allDone) {
                                finished.set(true)
                                return@submit
                            }
                            Thread.sleep(IDLE_SLEEP_MS)
                            continue
                        }

                        activeConnections.incrementAndGet()
                        try {
                            downloadChunk(request, part, index, chunkSize, total, cancelled) {
                                failuresSinceSample.incrementAndGet()
                            }
                            synchronized(lock) {
                                completed[index] = true
                                inFlight[index] = false
                                persistMeta(meta, request.url, total, chunkSize, completed)
                            }
                            val now = completedBytes.addAndGet(chunkLength(index, chunkSize, total))
                            emitProgress(now, total, startedAt, activeConnections.get(), onProgress)
                            if (now >= total) finished.set(true)
                        } catch (t: Throwable) {
                            synchronized(lock) { inFlight[index] = false }
                            throw t
                        } finally {
                            activeConnections.decrementAndGet()
                        }
                    }
                }
            }
            futures.forEach { it.get() }
        } finally {
            finished.set(true)
            controller.interrupt()
            pool.shutdownNow()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }

        if (cancelled.get()) throw InterruptedException("Download cancelled")
        check(completed.all { it }) { "Adaptive range download ended with missing chunks" }
        persistMeta(meta, request.url, total, chunkSize, completed)
        logger("FirmwareDownloader: adaptive ranged transfer complete; chunks=$chunkCount")
        return Result(part, resumed)
    }

    private fun downloadChunk(
        request: FirmwareDownloader.Request,
        part: File,
        index: Int,
        chunkSize: Long,
        total: Long,
        cancelled: AtomicBoolean,
        onFailure: () -> Unit
    ) {
        val start = index * chunkSize
        val end = chunkEnd(index, chunkSize, total)
        val expected = end - start + 1L
        var last: Throwable? = null

        for (attempt in 0..request.maxRetries) {
            checkCancelled(cancelled)
            var connection: HttpURLConnection? = null
            try {
                connection = open(request, start, end)
                val code = connection.responseCode
                if (code != 206) error("Chunk $index expected HTTP 206, got $code")
                val contentRange = connection.getHeaderField("Content-Range").orEmpty()
                if (!contentRange.startsWith("bytes $start-$end/")) {
                    error("Chunk $index Content-Range mismatch: $contentRange")
                }

                RandomAccessFile(part, "rw").use { output ->
                    output.seek(start)
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        var remaining = expected
                        while (remaining > 0L) {
                            checkCancelled(cancelled)
                            val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            remaining -= read.toLong()
                        }
                        if (remaining != 0L) error("Chunk $index incomplete: missing $remaining byte(s)")
                    }
                    output.fd.sync()
                }
                return
            } catch (t: Throwable) {
                if (t is InterruptedException || cancelled.get()) throw t
                last = t
                onFailure()
                if (attempt >= request.maxRetries) break
                val delayMs = min(30_000L, 1_000L shl min(attempt, 5))
                logger(
                    "FirmwareDownloader: chunk=$index attempt=${attempt + 1} failed: " +
                        "${t.javaClass.simpleName}: ${t.message}; retry in ${delayMs}ms"
                )
                sleepCancelled(delayMs, cancelled)
            } finally {
                connection?.disconnect()
            }
        }
        throw last ?: IllegalStateException("Chunk $index failed")
    }

    private fun open(request: FirmwareDownloader.Request, start: Long, end: Long): HttpURLConnection =
        (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = request.connectTimeoutMs
            readTimeout = request.readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "iDeviceRestore-Android/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Range", "bytes=$start-$end")
        }

    private fun chooseChunkSize(total: Long): Long = when {
        total >= 16L * 1024 * 1024 * 1024 -> 16L * 1024 * 1024
        total >= 4L * 1024 * 1024 * 1024 -> 8L * 1024 * 1024
        else -> 4L * 1024 * 1024
    }

    private fun chunkEnd(index: Int, chunkSize: Long, total: Long): Long =
        min(total - 1L, ((index + 1L) * chunkSize) - 1L)

    private fun chunkLength(index: Int, chunkSize: Long, total: Long): Long =
        chunkEnd(index, chunkSize, total) - (index * chunkSize) + 1L

    private fun loadMeta(
        meta: File,
        url: String,
        total: Long,
        chunkSize: Long,
        completed: BooleanArray
    ): Boolean {
        if (!meta.isFile) return false
        return runCatching {
            val lines = meta.readLines()
            if (lines.firstOrNull() != "version=1") return@runCatching false
            if (lines.find { it.startsWith("url=") }?.substringAfter("url=") != url) return@runCatching false
            if (lines.find { it.startsWith("size=") }?.substringAfter("size=")?.toLongOrNull() != total) return@runCatching false
            if (lines.find { it.startsWith("chunk=") }?.substringAfter("chunk=")?.toLongOrNull() != chunkSize) return@runCatching false
            val bits = lines.find { it.startsWith("completed=") }?.substringAfter("completed=") ?: return@runCatching false
            if (bits.length != completed.size) return@runCatching false
            bits.forEachIndexed { index, c -> completed[index] = c == '1' }
            true
        }.getOrElse {
            logger("FirmwareDownloader: ignoring invalid resume metadata: ${it.message}")
            false
        }
    }

    private fun persistMeta(
        meta: File,
        url: String,
        total: Long,
        chunkSize: Long,
        completed: BooleanArray
    ) {
        val temp = File(meta.absolutePath + ".tmp")
        val bits = buildString(completed.size) { completed.forEach { append(if (it) '1' else '0') } }
        temp.writeText(
            "version=1\n" +
                "url=$url\n" +
                "size=$total\n" +
                "chunk=$chunkSize\n" +
                "completed=$bits\n"
        )
        if (meta.exists() && !meta.delete()) error("Could not replace ${meta.absolutePath}")
        if (!temp.renameTo(meta)) {
            temp.copyTo(meta, overwrite = true)
            temp.delete()
        }
    }

    private fun emitProgress(
        bytes: Long,
        total: Long,
        startedAt: Long,
        active: Int,
        callback: (FirmwareDownloader.Progress) -> Unit
    ) {
        val elapsed = (System.nanoTime() - startedAt).coerceAtLeast(1L) / 1_000_000_000.0
        callback(
            FirmwareDownloader.Progress(
                downloadedBytes = bytes.coerceAtMost(total),
                totalBytes = total,
                bytesPerSecond = (bytes / elapsed).toLong(),
                activeConnections = active
            )
        )
    }

    private fun sleepCancelled(delayMs: Long, cancelled: AtomicBoolean) {
        var remaining = delayMs
        while (remaining > 0L) {
            checkCancelled(cancelled)
            val slice = min(remaining, 250L)
            Thread.sleep(slice)
            remaining -= slice
        }
    }

    private fun checkCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Download cancelled")
        }
    }

    companion object {
        private const val CONTROL_INTERVAL_MS = 5_000L
        private const val ADJUSTMENT_COOLDOWN_MS = 10_000L
        private const val IDLE_SLEEP_MS = 200L
        private const val MIN_SCALE_UP_BPS = 1L * 1024 * 1024
    }
}
