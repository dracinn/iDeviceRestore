package com.idevicerestore.android

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

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
                if (cancelled.get() || Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Firmware verification cancelled")
                }
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
}
