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
    data class Outcome(
        val file: File,
        val digestHex: String,
    )

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
        val partialMetadataFile = File(directory, "${request.fileName}.part.properties")
        val completeMetadataFile = File(directory, "${request.fileName}.complete.properties")

        existingCompletedFile(
            request = request,
            finalFile = finalFile,
            completeMetadataFile = completeMetadataFile,
            isCancelled = isCancelled,
        )?.let { return it }

        val metadata = loadMetadata(partialMetadataFile)
        val canResume = partialFile.isFile &&
            metadata.getProperty(KEY_URL) == request.url &&
            (metadata.getProperty(KEY_ETAG) != null || metadata.getProperty(KEY_LAST_MODIFIED) != null)

        if (partialFile.exists() && !canResume) {
            partialFile.delete()
            partialMetadataFile.delete()
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
            var code = connection.responseCode
            if (!connection.url.protocol.equals("https", ignoreCase = true)) {
                throw TransferException("Firmware server redirected to a non-HTTPS URL", retryable = false)
            }

            if (code == HttpURLConnection.HTTP_REQUESTED_RANGE_NOT_SATISFIABLE && resumeOffset > 0L) {
                connection.disconnect()
                partialFile.delete()
                partialMetadataFile.delete()
                metadata.clear()
                resumeOffset = 0L
                connection = openConnection(request, 0L, metadata)
                code = connection.responseCode
            }

            if (code !in 200..299) {
                val retryable = code == 408 || code == 429 || code in 500..599
                throw TransferException("Firmware server returned HTTP $code", retryable = retryable)
            }

            val append = resumeOffset > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (append) {
                val contentRange = connection.getHeaderField("Content-Range")
                if (contentRange == null || !contentRange.startsWith("bytes $resumeOffset-")) {
                    throw TransferException("Firmware server returned an invalid resume range", retryable = true)
                }
            } else if (resumeOffset > 0L) {
                resumeOffset = 0L
                if (partialFile.exists() && !partialFile.delete()) {
                    throw TransferException("Could not reset partial firmware file", retryable = false)
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
            saveMetadata(partialMetadataFile, metadata)

            FileOutputStream(partialFile, append).use { output ->
                BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = resumeOffset
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

            request.expectedBytes?.let { expected ->
                if (partialFile.length() != expected) {
                    throw TransferException(
                        "Firmware size mismatch: expected $expected bytes, received ${partialFile.length()}",
                        retryable = true,
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
            partialMetadataFile.delete()
            saveCompleteMetadata(
                completeMetadataFile,
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

    private fun existingCompletedFile(
        request: FirmwareDownloadRequest,
        finalFile: File,
        completeMetadataFile: File,
        isCancelled: () -> Boolean,
    ): Outcome? {
        if (!finalFile.isFile) return null

        request.expectedDigestAlgorithm?.let { algorithm ->
            val expected = request.expectedDigestHex ?: return@let
            val actual = digest(finalFile, algorithm, isCancelled)
            if (actual.equals(expected, ignoreCase = true)) {
                if (request.expectedBytes == null || finalFile.length() == request.expectedBytes) {
                    return Outcome(finalFile, actual)
                }
            }
            throw TransferException("Existing firmware file does not match the requested digest", retryable = false)
        }

        val metadata = loadMetadata(completeMetadataFile)
        val algorithm = metadata.getProperty(KEY_DIGEST_ALGORITHM) ?: return null
        val expectedDigest = metadata.getProperty(KEY_DIGEST_HEX) ?: return null
        val expectedLength = metadata.getProperty(KEY_BYTES)?.toLongOrNull() ?: return null
        if (metadata.getProperty(KEY_URL) != request.url || expectedLength != finalFile.length()) {
            return null
        }
        val actual = digest(finalFile, algorithm, isCancelled)
        if (!actual.equals(expectedDigest, ignoreCase = true)) return null
        return Outcome(finalFile, actual)
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
        if (file.isFile) {
            runCatching { file.inputStream().use { load(it) } }
        }
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
        val properties = Properties().apply {
            setProperty(KEY_URL, url)
            setProperty(KEY_BYTES, bytes.toString())
            setProperty(KEY_DIGEST_ALGORITHM, algorithm)
            setProperty(KEY_DIGEST_HEX, digestHex)
        }
        saveMetadata(file, properties)
    }

    private fun digest(file: File, algorithm: String, isCancelled: () -> Boolean): String {
        val canonical = when {
            algorithm.equals("SHA-1", true) -> "SHA-1"
            algorithm.equals("SHA-256", true) -> "SHA-256"
            else -> throw TransferException("Unsupported digest algorithm $algorithm", retryable = false)
        }
        val digest = MessageDigest.getInstance(canonical)
        FileInputStream(file).use { raw ->
            BufferedInputStream(raw, BUFFER_SIZE).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (isCancelled()) throw CancelledException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_ETAG = "etag"
        private const val KEY_LAST_MODIFIED = "lastModified"
        private const val KEY_BYTES = "bytes"
        private const val KEY_DIGEST_ALGORITHM = "digestAlgorithm"
        private const val KEY_DIGEST_HEX = "digestHex"
        private const val USER_AGENT = "iDeviceRestore-Android/0.1"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_INTERVAL_NANOS = 500_000_000L
    }
}
