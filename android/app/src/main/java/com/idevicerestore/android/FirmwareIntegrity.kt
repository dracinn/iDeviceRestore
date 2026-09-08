package com.idevicerestore.android

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

object FirmwareIntegrity {
    data class Verification(
        val sha1: String,
        val matches: Boolean
    )

    fun sha1(
        file: File,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onBytesHashed: (Long) -> Unit = {}
    ): String {
        require(file.isFile) { "Firmware file does not exist: ${file.absolutePath}" }
        val digest = MessageDigest.getInstance("SHA-1")
        var hashed = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
            while (true) {
                checkCancelled(cancelled)
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                hashed += count
                onBytesHashed(hashed)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySha1(
        file: File,
        expectedSha1: String,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onBytesHashed: (Long) -> Unit = {}
    ): Verification {
        val expected = expectedSha1.trim().lowercase()
        require(expected.matches(Regex("[0-9a-f]{40}"))) { "Invalid SHA-1 checksum" }
        val actual = sha1(file, cancelled, onBytesHashed)
        return Verification(actual, actual == expected)
    }

    /**
     * Performs a full ZIP stream validation for IPSWs that do not have catalog SHA-1 metadata.
     * Draining every entry makes ZipInputStream verify decompression/CRC data instead of trusting
     * only the final file length. A usable IPSW must also contain BuildManifest.plist.
     */
    fun validateIpswArchive(
        file: File,
        cancelled: AtomicBoolean = AtomicBoolean(false)
    ): Int {
        require(file.isFile && file.length() > 0L) { "Firmware file is missing or empty: ${file.absolutePath}" }
        var entries = 0
        var hasBuildManifest = false
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
        ZipInputStream(BufferedInputStream(FileInputStream(file), DEFAULT_BUFFER_SIZE * 16)).use { zip ->
            while (true) {
                checkCancelled(cancelled)
                val entry = zip.nextEntry ?: break
                entries++
                if (entry.name == "BuildManifest.plist" || entry.name.endsWith("/BuildManifest.plist")) {
                    hasBuildManifest = true
                }
                if (!entry.isDirectory) {
                    while (true) {
                        checkCancelled(cancelled)
                        val count = zip.read(buffer)
                        if (count < 0) break
                    }
                }
                zip.closeEntry()
            }
        }
        require(entries > 0) { "Firmware archive contains no ZIP entries" }
        require(hasBuildManifest) { "Firmware archive is missing BuildManifest.plist" }
        return entries
    }

    private fun checkCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Firmware verification cancelled")
        }
    }
}
