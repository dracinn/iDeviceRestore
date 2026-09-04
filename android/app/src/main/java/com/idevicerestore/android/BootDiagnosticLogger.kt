package com.idevicerestore.android

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BootDiagnosticLogger(context: Context) {
    val sessionDirectory: File
    val sessionLog: File
    val usbLog: File
    val summaryFile: File

    private val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val lineTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    init {
        val root = if (Environment.isExternalStorageManager()) {
            File(Environment.getExternalStorageDirectory(), "iDeviceRestore/Diagnostics")
        } else {
            File(context.getExternalFilesDir(null), "Diagnostics")
        }
        sessionDirectory = File(root, timestamp.format(Date())).apply { mkdirs() }
        sessionLog = File(sessionDirectory, "diagnostic.log")
        usbLog = File(sessionDirectory, "usb-events.log")
        summaryFile = File(sessionDirectory, "summary.txt")
    }

    @Synchronized
    fun log(message: String) {
        append(sessionLog, message)
    }

    @Synchronized
    fun logUsb(message: String) {
        append(usbLog, message)
        append(sessionLog, "USB: $message")
    }

    @Synchronized
    fun writeSummary(snapshot: BootDiagnosticSnapshot) {
        val text = buildString {
            appendLine("iDeviceRestore Boot Diagnostics")
            appendLine("Generated: ${lineTimestamp.format(Date())}")
            appendLine("State: ${snapshot.state}")
            appendLine("Device: ${snapshot.deviceDescription ?: "none"}")
            appendLine()
            appendLine("Findings")
            if (snapshot.findings.isEmpty()) {
                appendLine("- No conclusive finding yet.")
            } else {
                snapshot.findings.forEach { finding ->
                    appendLine("- [${finding.confidence}] ${finding.title}")
                    appendLine("  ${finding.detail}")
                    finding.recommendation?.let { appendLine("  Recommendation: $it") }
                }
            }
            appendLine()
            appendLine("Timeline")
            snapshot.events.forEach { event ->
                appendLine("- ${event.timestamp}: ${event.state}: ${event.message}")
            }
        }
        summaryFile.writeText(text)
    }

    private fun append(file: File, message: String) {
        file.parentFile?.mkdirs()
        file.appendText("${lineTimestamp.format(Date())} $message\n")
    }
}
