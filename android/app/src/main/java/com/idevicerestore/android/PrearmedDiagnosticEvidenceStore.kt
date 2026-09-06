package com.idevicerestore.android

import android.os.SystemClock
import java.time.Instant

/**
 * Process-local, lifecycle-independent evidence for the bounded pre-armed iBSS -> Stage-1 -> iBEC
 * diagnostic. This deliberately contains only diagnostic messages; raw ECID, nonces and ticket
 * bytes must never be written here.
 */
object PrearmedDiagnosticEvidenceStore {
    private val lock = Any()
    private val lines = ArrayList<String>()
    private var sessionStartedElapsedMs: Long? = null

    fun begin(expectedStage1Build: String) = synchronized(lock) {
        lines.clear()
        sessionStartedElapsedMs = SystemClock.elapsedRealtime()
        appendLocked("session begin expectedStage1Build=$expectedStage1Build")
    }

    fun append(message: String) = synchronized(lock) {
        appendLocked(message.trimEnd())
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) {
        lines.clear()
        sessionStartedElapsedMs = null
    }

    private fun appendLocked(message: String) {
        val elapsed = sessionStartedElapsedMs?.let { SystemClock.elapsedRealtime() - it }
        val elapsedText = elapsed?.let { "+${it}ms" } ?: "+unknown"
        lines += "${Instant.now()} $elapsedText $message"
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    private const val MAX_LINES = 512
}
