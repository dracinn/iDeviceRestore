package com.idevicerestore.android

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BootDiagnosticLogger(context: Context) {
    private val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val lineTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val sessionStamp = timestamp.format(Date())
    private val rootDirectory: File

    var sessionDirectory: File
        private set
    var sessionLog: File
        private set
    var usbLog: File
        private set
    var summaryFile: File
        private set

    init {
        val canUseProjectRoot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        rootDirectory = if (canUseProjectRoot) {
            File(Environment.getExternalStorageDirectory(), "iDeviceRestore/Diagnostics")
        } else {
            File(context.getExternalFilesDir(null), "Diagnostics")
        }
        sessionDirectory = File(rootDirectory, sessionStamp).apply { mkdirs() }
        sessionLog = File(sessionDirectory, "diagnostic.log")
        usbLog = File(sessionDirectory, "usb-events.log")
        summaryFile = File(sessionDirectory, "summary.txt")
    }

    @Synchronized
    fun organizeForDevice(device: UsbDevice) {
        if (sessionDirectory.parentFile != rootDirectory) return
        val ids = AppleUsb.bootIdentifiers(device)
        val deviceKey = when {
            ids?.cpidHex != null && ids.bdidHex != null -> "CPID-${ids.cpidHex}_BDID-${ids.bdidHex}"
            ids?.cpidHex != null -> "CPID-${ids.cpidHex}"
            else -> "PID-%04X".format(device.productId)
        }.replace(Regex("[^A-Za-z0-9._-]"), "_")

        val target = File(File(rootDirectory, deviceKey), sessionStamp)
        if (target == sessionDirectory) return
        target.mkdirs()
        listOf(sessionLog, usbLog, summaryFile).forEach { source ->
            if (!source.exists()) return@forEach
            val destination = File(target, source.name)
            if (!source.renameTo(destination)) {
                source.copyTo(destination, overwrite = true)
                source.delete()
            }
        }
        sessionDirectory.delete()
        sessionDirectory = target
        sessionLog = File(target, "diagnostic.log")
        usbLog = File(target, "usb-events.log")
        summaryFile = File(target, "summary.txt")
        log("Diagnostic session organized under device key: $deviceKey")
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
            snapshot.recovery?.readiness?.let { readiness ->
                appendLine("Recovery command transport: ${if (readiness.commandTransportReady) "responsive" else "not confirmed"}")
                appendLine("Recovery build: ${readiness.buildVersion ?: "unknown"}")
                appendLine("Recovery style: ${readiness.buildStyle ?: "unknown"}")
                appendLine("Auto boot: ${readiness.autoBoot ?: "unknown"}")
                appendLine("Boot stage: ${readiness.bootStage ?: "unknown"}")
            }
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
