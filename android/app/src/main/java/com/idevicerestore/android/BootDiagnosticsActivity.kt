package com.idevicerestore.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class BootDiagnosticsActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private lateinit var logger: BootDiagnosticLogger
    private lateinit var engine: BootDiagnosticEngine
    private lateinit var stateView: TextView
    private lateinit var deviceView: TextView
    private lateinit var recoveryView: TextView
    private lateinit var findingsView: TextView
    private lateinit var timelineView: TextView
    private lateinit var logPathView: TextView
    private lateinit var runButton: Button
    private val worker = Executors.newSingleThreadExecutor()
    private val permissionAction by lazy { "${packageName}.BOOT_DIAGNOSTICS_USB_PERMISSION" }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.usbDevice()
                    if (device != null && !usbManager.hasPermission(device)) {
                        worker.execute { engine.recordAttach(device.deviceName) }
                        requestUsbPermission(device)
                        stateView.text = "USB permission required"
                        deviceView.text = AppleUsb.describe(device)
                    } else {
                        queueScan { engine.recordAttach(device?.deviceName) }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val deviceName = intent.usbDevice()?.deviceName
                    queueScan { engine.recordDetach(deviceName) }
                }
                permissionAction -> queueScan()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Boot Diagnostics"
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        logger = BootDiagnosticLogger(this)
        engine = BootDiagnosticEngine(usbManager, logger)
        setContentView(buildContentView())

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(permissionAction)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)

        logger.log("Boot diagnostic session started")
        logger.log("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        logger.log("Read-only mode: no boot, reboot, environment mutation, upload, revive, or restore commands")
        logPathView.text = "Session folder\n${logger.sessionDirectory.absolutePath}"
        runDiagnostic(requestPermission = true)
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Boot Diagnostics"
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Observe Apple USB boot states and run read-only Recovery/iBoot checks without starting a restore."
            textSize = 14f
            setPadding(0, dp(4), 0, dp(14))
        })

        stateView = section(root, "Current state", "Waiting for scan", 20f)
        deviceView = section(root, "Detected device", "No Apple USB device", 14f)
        recoveryView = section(root, "Recovery details", "No Recovery snapshot yet", 13f, monospace = true)

        runButton = Button(this).apply {
            text = "Run diagnostic scan"
            setOnClickListener { runDiagnostic(requestPermission = true) }
        }
        root.addView(runButton)

        findingsView = section(root, "Findings", "No findings yet", 14f)
        timelineView = section(root, "Diagnostic timeline", "No events yet", 12f, monospace = true)
        logPathView = section(root, "Separate diagnostic logs", "Preparing session folder…", 12f, monospace = true)

        root.addView(TextView(this).apply {
            text = "This module reports only what can be supported by externally observable USB/Recovery evidence. Internal hardware faults may remain indeterminate."
            textSize = 12f
            setPadding(0, dp(16), 0, 0)
        })
        return scroll
    }

    private fun section(
        root: LinearLayout,
        heading: String,
        initial: String,
        size: Float,
        monospace: Boolean = false
    ): TextView {
        val density = resources.displayMetrics.density
        val margin = (14 * density).toInt()
        root.addView(TextView(this).apply {
            text = heading
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, margin, 0, (4 * density).toInt())
        })
        return TextView(this).also { value ->
            value.text = initial
            value.textSize = size
            value.setTextIsSelectable(true)
            if (monospace) value.typeface = android.graphics.Typeface.MONOSPACE
            root.addView(value)
        }
    }

    private fun runDiagnostic(requestPermission: Boolean) {
        if (requestPermission) {
            val device = preferredAppleDevice()
            if (device != null && !usbManager.hasPermission(device)) {
                requestUsbPermission(device)
                stateView.text = "USB permission required"
                deviceView.text = AppleUsb.describe(device)
                return
            }
        }
        queueScan()
    }

    private fun queueScan(beforeScan: (() -> Unit)? = null) {
        runButton.isEnabled = false
        stateView.text = "Scanning…"
        worker.execute {
            beforeScan?.invoke()
            val snapshot = runCatching { engine.scan() }.getOrElse { error ->
                logger.log("Diagnostic scan failed: ${error.message ?: error.javaClass.simpleName}")
                BootDiagnosticSnapshot(
                    state = BootDiagnosticState.USB_ERROR,
                    deviceDescription = null,
                    events = emptyList(),
                    findings = listOf(
                        BootDiagnosticFinding(
                            title = "Diagnostic scan failed",
                            confidence = DiagnosticConfidence.INSUFFICIENT_EVIDENCE,
                            detail = error.message ?: error.javaClass.simpleName
                        )
                    )
                )
            }
            runOnUiThread {
                render(snapshot)
                runButton.isEnabled = true
            }
        }
    }

    private fun render(snapshot: BootDiagnosticSnapshot) {
        stateView.text = snapshot.state.name.replace('_', ' ')
        deviceView.text = snapshot.deviceDescription ?: "No Apple USB device detected"
        recoveryView.text = snapshot.recovery?.let { recovery ->
            buildString {
                appendLine("commandTransportReady=${recovery.readiness.commandTransportReady}")
                appendLine("buildVersion=${recovery.readiness.buildVersion ?: "unknown"}")
                appendLine("buildStyle=${recovery.readiness.buildStyle ?: "unknown"}")
                appendLine("autoBoot=${recovery.readiness.autoBoot ?: "unknown"}")
                appendLine("bootStage=${recovery.readiness.bootStage ?: "unknown"}")
                recovery.variables.forEach { variable ->
                    append("${variable.name}=")
                    appendLine(variable.result?.value ?: variable.error?.message ?: "no response")
                }
                append("consoleBytes=${recovery.console?.bytes ?: 0}")
            }
        } ?: "No Recovery snapshot yet"
        findingsView.text = if (snapshot.findings.isEmpty()) {
            "No conclusive finding yet. Connect the affected Mac in its current boot state and scan again."
        } else {
            snapshot.findings.joinToString("\n\n") { finding ->
                buildString {
                    append("[${finding.confidence}] ${finding.title}\n")
                    append(finding.detail)
                    finding.recommendation?.let { append("\nRecommendation: $it") }
                }
            }
        }
        timelineView.text = snapshot.events.takeLast(40).joinToString("\n") { event ->
            "${event.timestamp}  ${event.state}  ${event.message}"
        }.ifBlank { "No events yet" }
        logPathView.text = buildString {
            append("Session folder\n${logger.sessionDirectory.absolutePath}\n\n")
            append("diagnostic.log\nusb-events.log\nsummary.txt")
        }
    }

    private fun preferredAppleDevice(): UsbDevice? {
        val apple = usbManager.deviceList.values.filter { it.vendorId == AppleUsb.APPLE_VID }
        return apple.firstOrNull { AppleUsb.mode(it) != AppleUsb.Mode.APPLE_OTHER } ?: apple.firstOrNull()
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val intent = Intent(permissionAction).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(this, 20, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
