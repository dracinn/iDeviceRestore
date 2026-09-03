package io.github.dracinn.idevicerestore.download

import android.content.Context
import android.os.Environment
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties

internal class FirmwareDownloadEngine(private val context: Context) {
    data class Outcome(val file: File, val digestHex: String)

    class TransferException(
        message: String,
        cause: Throwable? = null,
        val retryable: Boolean = true,
    ) : IOException(message, cause)

    class CancelledException : IOException("Firmware download cancelled")

    fun execute(
        request: FirmwareDownloadRequest,
        isCancelled: () -> Boolean,
        onProgress: (FirmwareDownloadProgress) -> Unit,
    ): Outcome {
        val directory = firmwareDirectory()
        val finalFile = File(directory, request.fileName)
        val partialFile = File(directory, "${request.fileName}.part")
        val partialMetadata = File(directory, "${request.fileName}.part.properties")
        val completeMetadata = File(directory, "${request.fileName}.complete.properties")

        if (finalFile.exists()) {
            return validateCompletedFile(request, finalFile, completeMetadata, isCancelled)
        }

        val metadata = loadMetadata(partialMetadata)
        val hasValidator = metadata.getProperty(KEY_ETAG) != null ||
            metadata.getProperty(KEY_LAST_MODIFIED) != null
        val canResume = partialFile.isFile &&
            metadata.getProperty(KEY_URL) == request.url &&
            hasValidator

        if (partialFile.exists() && !canResume) {
            if (!partialFile.delete()) {
                throw TransferException("Could not reset unverified partial firmware", retryable = false)
            }
            partialMetadata.delete()
            metadata.clear()
        }

        var resumeOffset = partialFile.takeIf { it.isFile }?.length() ?: 0L
        onProgress(
            FirmwareDownloadProgress(
                requestId = request.id,
                phase = FirmwareDownloadPhase.CONNECTING,
                bytesDownloaded = resumeOffset,
                totalBytes = request.expectedBytes,
            )
        )

        var connection = openConnection(request, resumeOffset, metadata)
        try {
            var code = responseCode(connection)

            if (code == HTTP_RANGE_NOT_SATISFIABLE && resumeOffset > 0L) {
                connection.disconnect()
                if (!partialFile.delete()) {
                    throw TransferException("Could not reset stale partial firmware", retryable = false)
                }
                partialMetadata.delete()
                metadata.clear()
                resumeOffset = 0L
                connection = openConnection(request, 0L, metadata)
                code = responseCode(connection)
            }

            if (code !in 200..299) {
                val retryable = code == 408 || code == 429 || code in 500..599
                throw TransferException("Firmware server returned HTTP $code", retryable = retryable)
            }

            val append = resumeOffset > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (append) {
                val contentRange = connection.getHeaderField("Content-Range")
                if (contentRange == null || !contentRange.startsWith("bytes $resumeOffset-")) {
                    throw TransferException("Firmware server returned an invalid resume range")
                }
            } else if (resumeOffset > 0L) {
                resumeOffset = 0L
                if (!partialFile.delete()) {
                    throw TransferException("Could not restart partial firmware", retryable = false)
                }
            }

            val responseBytes = connection.getHeaderFieldLong("Content-Length", -1L)
            val totalBytes = when {
                request.expectedBytes != null -> request.expectedBytes
                responseBytes >= 0L -> resumeOffset + responseBytes
                else -> null
            }
            val remaining = totalBytes?.minus(resumeOffset)
            val usableSpace = directory.usableSpace
            if (remaining != null && usableSpace > 0L && remaining > usableSpace) {
                throw TransferException("Not enough free storage for firmware download", retryable = false)
            }

            metadata.setProperty(KEY_URL, request.url)
            connection.getHeaderField("ETag")?.let { metadata.setProperty(KEY_ETAG, it) }
            connection.getHeaderField("Last-Modified")?.let {
                metadata.setProperty(KEY_LAST_MODIFIED, it)
            }
            saveMetadata(partialMetadata, metadata)

            transferBody(
                request = request,
                connection = connection,
                partialFile = partialFile,
                append = append,
                startOffset = resumeOffset,
                totalBytes = totalBytes,
                isCancelled = isCancelled,
                onProgress = onProgress,
            )

            request.expectedBytes?.let { expected ->
                if (partialFile.length() != expected) {
                    throw TransferException(
                        "Firmware size mismatch: expected $expected bytes, received ${partialFile.length()}",
                    )
                }
            }

            onProgress(
                FirmwareDownloadProgress(
                    requestId = request.id,
                    phase = FirmwareDownloadPhase.VERIFYING,
                    bytesDownloaded = partialFile.length(),
                    totalBytes = request.expectedBytes ?: partialFile.length(),
                )
            )

            val algorithm = request.expectedDigestAlgorithm ?: "SHA-256"
            val digestHex = digest(partialFile, algorithm, isCancelled)
            request.expectedDigestHex?.let { expected ->
                if (!digestHex.equals(expected, ignoreCase = true)) {
                    throw TransferException("Firmware digest verification failed", retryable = false)
                }
            }

            if (!partialFile.renameTo(finalFile)) {
                throw TransferException("Could not finalize firmware file", retryable = false)
            }
            partialMetadata.delete()
            saveCompleteMetadata(
                completeMetadata,
                request.url,
                finalFile.length(),
                algorithm,
                digestHex,
            )
            return Outcome(finalFile, digestHex)
        } catch (e: CancelledException) {
            throw e
        } catch (e: TransferException) {
            throw e
        } catch (e: IOException) {
            throw TransferException(
                "Firmware transfer failed: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun transferBody(
        request: FirmwareDownloadRequest,
        connection: HttpURLConnection,
        partialFile: File,
        append: Boolean,
        startOffset: Long,
        totalBytes: Long?,
        isCancelled: () -> Boolean,
        onProgress: (FirmwareDownloadProgress) -> Unit,
    ) {
        FileOutputStream(partialFile, append).use { output ->
            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var downloaded = startOffset
                var lastReportAt = System.nanoTime()
                while (true) {
                    if (isCancelled()) throw CancelledException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read

                    val now = System.nanoTime()
                    if (now - lastReportAt >= PROGRESS_INTERVAL_NANOS) {
                        onProgress(
                            FirmwareDownloadProgress(
                                requestId = request.id,
                                phase = FirmwareDownloadPhase.DOWNLOADING,
                                bytesDownloaded = downloaded,
                                totalBytes = totalBytes,
                            )
                        )
                        lastReportAt = now
                    }
                }
                output.fd.sync()
                onProgress(
                    FirmwareDownloadProgress(
                        requestId = request.id,
                        phase = FirmwareDownloadPhase.DOWNLOADING,
                        bytesDownloaded = downloaded,
                        totalBytes = totalBytes,
                    )
                )
            }
        }
    }

    private fun validateCompletedFile(
        request: FirmwareDownloadRequest,
        file: File,
        metadataFile: File,
        isCancelled: () -> Boolean,
    ): Outcome {
        request.expectedBytes?.let { expected ->
            if (file.length() != expected) {
                throw TransferException("Existing firmware size does not match the request", retryable = false)
            }
        }

        request.expectedDigestAlgorithm?.let { algorithm ->
            val expected = requireNotNull(request.expectedDigestHex)
            val actual = digest(file, algorithm, isCancelled)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw TransferException("Existing firmware digest does not match the request", retryable = false)
            }
            return Outcome(file, actual)
        }

        val metadata = loadMetadata(metadataFile)
        val algorithm = metadata.getProperty(KEY_DIGEST_ALGORITHM)
            ?: throw TransferException("Existing firmware is not verified", retryable = false)
        val expectedDigest = metadata.getProperty(KEY_DIGEST_HEX)
            ?: throw TransferException("Existing firmware is not verified", retryable = false)
        val expectedLength = metadata.getProperty(KEY_BYTES)?.toLongOrNull()
            ?: throw TransferException("Existing firmware is not verified", retryable = false)
        if (metadata.getProperty(KEY_URL) != request.url || expectedLength != file.length()) {
            throw TransferException("Existing firmware does not match the request", retryable = false)
        }
        val actual = digest(file, algorithm, isCancelled)
        if (!actual.equals(expectedDigest, ignoreCase = true)) {
            throw TransferException("Existing firmware verification failed", retryable = false)
        }
        return Outcome(file, actual)
    }

    private fun responseCode(connection: HttpURLConnection): Int {
        val code = connection.responseCode
        if (!connection.url.protocol.equals("https", ignoreCase = true)) {
            throw TransferException("Firmware server redirected to a non-HTTPS URL", retryable = false)
        }
        return code
    }

    private fun openConnection(
        request: FirmwareDownloadRequest,
        resumeOffset: Long,
        metadata: Properties,
    ): HttpURLConnection {
        val url = URL(request.url)
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw TransferException("Firmware downloads must use HTTPS", retryable = false)
        }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Encoding", "identity")
            if (resumeOffset > 0L) {
                setRequestProperty("Range", "bytes=$resumeOffset-")
                metadata.getProperty(KEY_ETAG)?.let { setRequestProperty("If-Range", it) }
                    ?: metadata.getProperty(KEY_LAST_MODIFIED)?.let {
                        setRequestProperty("If-Range", it)
                    }
            }
        }
    }

    private fun firmwareDirectory(): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(root, "firmware").also {
            if (!it.exists() && !it.mkdirs()) {
                throw TransferException("Could not create firmware storage directory", retryable = false)
            }
        }
    }

    private fun loadMetadata(file: File): Properties = Properties().apply {
        if (file.isFile) runCatching { file.inputStream().use { load(it) } }
    }

    private fun saveMetadata(file: File, properties: Properties) {
        file.outputStream().use { properties.store(it, "iDeviceRestore Android firmware metadata") }
    }

    private fun saveCompleteMetadata(
        file: File,
        url: String,
        bytes: Long,
        algorithm: String,
        digestHex: String,
    ) {
        saveMetadata(
            file,
            Properties().apply {
                setProperty(KEY_URL, url)
                setProperty(KEY_BYTES, bytes.toString())
                setProperty(KEY_DIGEST_ALGORITHM, algorithm)
                setProperty(KEY_DIGEST_HEX, digestHex)
            },
        )
    }

    private fun digest(file: File, algorithm: String, isCancelled: () -> Boolean): String {
        val canonical = when {
            algorithm.equals("SHA-1", true) -> "SHA-1"
            algorithm.equals("SHA-256", true) -> "SHA-256"
            else -> throw TransferException("Unsupported digest algorithm $algorithm", retryable = false)
        }
        val messageDigest = MessageDigest.getInstance(canonical)
        FileInputStream(file).use { raw ->
            BufferedInputStream(raw, BUFFER_SIZE).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (isCancelled()) throw CancelledException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    messageDigest.update(buffer, 0, read)
                }
            }
        }
        return messageDigest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_ETAG = "etag"
        private const val KEY_LAST_MODIFIED = "lastModified"
        private const val KEY_BYTES = "bytes"
        private const val KEY_DIGEST_ALGORITHM = "digestAlgorithm"
        private const val KEY_DIGEST_HEX = "digestHex"
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val USER_AGENT = "iDeviceRestore-Android/0.1"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_INTERVAL_NANOS = 500_000_000L
    }
}
