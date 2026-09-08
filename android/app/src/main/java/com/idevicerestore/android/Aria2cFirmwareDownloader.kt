package com.idevicerestore.android

import android.content.Context
import android.os.Build
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Production firmware downloader backed by the official aria2c Android executable from aria2/aria2.
 * No custom segmented HTTP implementation is used on this path.
 */
internal class Aria2cFirmwareDownloader(
    context: Context,
    private val logger: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext

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
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            "This build requires an arm64-v8a Android device for the official aria2c executable"
        }
        request.destination.parentFile?.mkdirs()

        val aria2c = requireOfficialAria2c()
        val caBundle = writeAndroidCaBundle()
        val part = File(request.destination.absolutePath + ".part")
        val aria2Control = File(part.absolutePath + ".aria2")
        val oldAdaptiveMeta = File(request.destination.absolutePath + ".part.meta")
        val oldAdaptiveMetaTemp = File(request.destination.absolutePath + ".part.meta.tmp")
        if (oldAdaptiveMeta.exists() || oldAdaptiveMetaTemp.exists()) {
            logger("aria2c: removing obsolete custom-adaptive state before real aria2 resume")
            if (part.exists() && !part.delete()) error("Could not remove obsolete adaptive partial")
            oldAdaptiveMeta.delete()
            oldAdaptiveMetaTemp.delete()
            aria2Control.delete()
        }

        val resumed = part.isFile || aria2Control.isFile
        val connectionCount = request.connections.coerceIn(1, 16)
        val command = mutableListOf(
            aria2c.absolutePath,
            "--continue=true",
            "--auto-file-renaming=false",
            "--allow-overwrite=true",
            "--file-allocation=none",
            "--check-certificate=true",
            "--ca-certificate=${caBundle.absolutePath}",
            "--max-connection-per-server=$connectionCount",
            "--split=$connectionCount",
            "--min-split-size=1M",
            "--max-tries=${request.maxRetries.coerceAtLeast(1)}",
            "--retry-wait=2",
            "--connect-timeout=${(request.connectTimeoutMs / 1000).coerceAtLeast(1)}",
            "--timeout=${(request.readTimeoutMs / 1000).coerceAtLeast(1)}",
            "--summary-interval=1",
            "--console-log-level=notice",
            "--download-result=hide",
            "--human-readable=true",
            "--user-agent=iDeviceRestore-Android/${BuildConfig.VERSION_NAME}",
            "--dir=${request.destination.parentFile?.absolutePath ?: "."}",
            "--out=${part.name}",
            request.url
        )

        logger("aria2c: starting official aria2 $ARIA2_VERSION split=$connectionCount resume=$resumed")
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val outputThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (cancelled.get()) return@forEach
                    parseProgress(line, request.expectedSize)?.let(onProgress)
                    if (line.contains("[ERROR]", ignoreCase = true) || line.contains("[WARN]", ignoreCase = true)) {
                        logger("aria2c: ${line.trim()}")
                    }
                }
            }
        }.apply {
            name = "aria2c-output"
            isDaemon = true
            start()
        }

        try {
            while (process.isAlive) {
                if (cancelled.get() || Thread.currentThread().isInterrupted) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                    throw InterruptedException("aria2c firmware download cancelled")
                }
                Thread.sleep(100L)
            }
            outputThread.join(1_000L)
            val exit = process.exitValue()
            if (exit != 0) error("aria2c exited with code $exit")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }

        if (!part.isFile) error("aria2c completed without producing ${part.absolutePath}")
        val actualSize = part.length()
        if (request.expectedSize > 0L && actualSize != request.expectedSize) {
            error("Firmware size mismatch after aria2c: expected ${request.expectedSize}, got $actualSize")
        }

        val expectedSha1 = request.expectedSha1?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val sha1 = if (expectedSha1 != null) {
            logger("aria2c: transfer complete; verifying SHA-1")
            val actual = FirmwareIntegrity.sha1(part, cancelled)
            if (actual != expectedSha1) {
                part.delete()
                aria2Control.delete()
                error("SHA-1 mismatch after aria2c: expected $expectedSha1, got $actual")
            }
            actual
        } else {
            logger("aria2c: no catalog SHA-1; validating complete IPSW archive")
            try {
                FirmwareIntegrity.validateIpswArchive(part, cancelled)
            } catch (t: Throwable) {
                part.delete()
                aria2Control.delete()
                throw t
            }
            ""
        }

        if (request.destination.exists() && !request.destination.delete()) {
            error("Could not replace ${request.destination.absolutePath}")
        }
        if (!part.renameTo(request.destination)) {
            error("Could not promote completed aria2c firmware: ${request.destination.absolutePath}")
        }
        aria2Control.delete()
        onProgress(
            FirmwareDownloader.Progress(
                downloadedBytes = request.destination.length(),
                totalBytes = request.destination.length(),
                bytesPerSecond = 0L,
                activeConnections = 0
            )
        )
        logger("aria2c: firmware download complete and verified")
        return FirmwareDownloader.Result(
            file = request.destination,
            bytes = request.destination.length(),
            sha1 = sha1,
            resumed = resumed,
            segmented = connectionCount > 1
        )
    }

    private fun requireOfficialAria2c(): File {
        val binary = File(appContext.applicationInfo.nativeLibraryDir, "libaria2c.so")
        require(binary.isFile && binary.length() > 0L) {
            "Official aria2c executable is missing from the APK"
        }
        val process = ProcessBuilder(binary.absolutePath, "--version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("aria2c --version timed out")
        }
        require(process.exitValue() == 0 && output.contains("aria2 version $ARIA2_VERSION")) {
            "Bundled downloader is not the expected official aria2c $ARIA2_VERSION"
        }
        logger("aria2c: verified official runtime: ${output.lineSequence().firstOrNull().orEmpty().trim()}")
        return binary
    }

    /** aria2's Android notes call out the non-Unix CA layout; export Android's live trust store. */
    private fun writeAndroidCaBundle(): File {
        val directory = File(appContext.cacheDir, "aria2c").apply { mkdirs() }
        val bundle = File(directory, "android-ca-bundle.pem")
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: error("Android X509 trust manager unavailable")
        val issuers = manager.acceptedIssuers
        require(issuers.isNotEmpty()) { "Android trust store contains no accepted issuers" }
        bundle.bufferedWriter().use { writer ->
            issuers.forEach { certificate ->
                val encoded = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded)
                writer.appendLine("-----BEGIN CERTIFICATE-----")
                writer.appendLine(encoded)
                writer.appendLine("-----END CERTIFICATE-----")
            }
        }
        logger("aria2c: exported ${issuers.size} Android trusted CA certificates")
        return bundle
    }

    private fun parseProgress(line: String, expectedSize: Long): FirmwareDownloader.Progress? {
        val match = PROGRESS.find(line) ?: return null
        val downloaded = parseHumanBytes(match.groupValues[1]) ?: return null
        val totalFromAria = parseHumanBytes(match.groupValues[2]) ?: -1L
        val total = expectedSize.takeIf { it > 0L } ?: totalFromAria
        val connections = match.groupValues[3].toIntOrNull() ?: 0
        val speed = parseHumanBytes(match.groupValues[4]) ?: 0L
        return FirmwareDownloader.Progress(
            downloadedBytes = if (total > 0L) downloaded.coerceAtMost(total) else downloaded,
            totalBytes = total,
            bytesPerSecond = speed,
            activeConnections = connections
        )
    }

    private fun parseHumanBytes(raw: String): Long? {
        val match = HUMAN_BYTES.matchEntire(raw.trim()) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2]) {
            "", "B" -> 1.0
            "KiB" -> 1024.0
            "MiB" -> 1024.0 * 1024.0
            "GiB" -> 1024.0 * 1024.0 * 1024.0
            "TiB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            "KB" -> 1000.0
            "MB" -> 1000.0 * 1000.0
            "GB" -> 1000.0 * 1000.0 * 1000.0
            else -> return null
        }
        return (value * multiplier).toLong()
    }

    companion object {
        private const val ARIA2_VERSION = "1.37.0"
        private val PROGRESS = Regex(
            """\[#\w+\s+([0-9.]+(?:KiB|MiB|GiB|TiB|KB|MB|GB|B)?)/([0-9.]+(?:KiB|MiB|GiB|TiB|KB|MB|GB|B)?)\([^)]*\).*?CN:(\d+).*?DL:([0-9.]+(?:KiB|MiB|GiB|TiB|KB|MB|GB|B)?)"""
        )
        private val HUMAN_BYTES = Regex("""([0-9.]+)(KiB|MiB|GiB|TiB|KB|MB|GB|B)?""")
    }
}
