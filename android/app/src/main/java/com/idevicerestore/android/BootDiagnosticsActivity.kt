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
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.Executors

class BootDiagnosticsActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private lateinit var logger: BootDiagnosticLogger
    private lateinit var engine: BootDiagnosticEngine
    private lateinit var stateView: TextView
    private lateinit var deviceView: TextView
    private lateinit var recoveryView: TextView
    private lateinit var testMatrixView: TextView
    private lateinit var findingsView: TextView
    private lateinit var timelineView: TextView
    private lateinit var logPathView: TextView
    private lateinit var activeTestLogView: TextView
    private lateinit var runButton: Button
    private lateinit var progressSummaryView: TextView
    private lateinit var diagnosticsProgress: ProgressBar
    private lateinit var connectionStatusView: TextView
    private lateinit var hardwareStatusView: TextView
    private lateinit var bootStatusView: TextView
    private lateinit var recoveryStatusView: TextView
    private lateinit var logsStatusView: TextView
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
        title = "Diagnostics"
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

        logger.log("Boot diagnostic development session started")
        logger.log("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        logger.log("Policy: Boot Diagnostics is the unrestricted development and hardware-test surface; only verified functions are promoted to the main app")
        logger.log("Individual development tests may define their own prerequisites, confirmations, and current stop boundaries while they are being proven")
        logPathView.text = "${logger.sessionDirectory.absolutePath}\n\ndiagnostic.log\nusb-events.log\nsummary.txt"
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
        val primary = ContextCompat.getColor(this, R.color.mock_text_primary)
        val secondary = ContextCompat.getColor(this, R.color.mock_text_secondary)
        val blue = ContextCompat.getColor(this, R.color.mock_primary)
        val separator = ContextCompat.getColor(this, R.color.mock_separator)
        val surface = ContextCompat.getColor(this, R.color.mock_surface)
        val background = ContextCompat.getColor(this, R.color.mock_background)

        fun label(text: String, size: Float = 11f, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(if (bold) primary else secondary)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        fun card(): MaterialCardView = MaterialCardView(this).apply {
            radius = dp(13).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = separator
            setCardBackgroundColor(surface)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(background)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(20))
        }
        scroll.addView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 29f
            setTextColor(primary)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(34), dp(40)))
        header.addView(TextView(this).apply {
            text = "Diagnostics"
            textSize = 21f
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(primary)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), dp(40)))
        root.addView(header)
        root.addView(label("Analyze why your device won't boot.", 10f).apply {
            setPadding(dp(35), 0, 0, dp(9))
        })

        val checklist = card()
        val checklistBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        checklist.addView(checklistBody)

        fun addRow(icon: String, title: String, subtitle: String, initial: String, active: Boolean = true): TextView {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(51))
            }
            row.addView(TextView(this).apply {
                text = icon
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(if (active) blue else secondary)
                setBackgroundResource(R.drawable.mock_reference_status_pill)
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(title, 11f, true))
                addView(label(subtitle, 9f))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(9) })
            val status = TextView(this).apply {
                text = initial
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(if (active) blue else secondary)
            }
            row.addView(status, LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.MATCH_PARENT))
            checklistBody.addView(row)
            if (checklistBody.childCount < 8) checklistBody.addView(View(this).apply { setBackgroundColor(separator) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            return status
        }

        connectionStatusView = addRow("▣", "Device Connection", "Checking USB and mode...", "○")
        hardwareStatusView = addRow("▤", "Hardware Info", "Reading device information...", "○")
        bootStatusView = addRow("◴", "Boot Analysis", "Checking for common issues...", "○")
        addRow("▥", "Storage Health", "Available for diagnostic development", "○", active = false)
        recoveryStatusView = addRow("◷", "Recovery Environment", "Verifying recovery and firmware...", "○")
        addRow("▣", "NVRAM", "Available for diagnostic development", "○", active = false)
        addRow("▤", "Startup Disk", "Available for diagnostic development", "○", active = false)
        logsStatusView = addRow("▤", "Logs", "Collecting and analyzing logs...", "○")
        root.addView(checklist)

        val progressCard = card().apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9) }
        }
        val progressBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        progressCard.addView(progressBody)
        val progressTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        progressTop.addView(TextView(this).apply {
            text = "⌁"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(blue)
            setBackgroundResource(R.drawable.mock_reference_status_pill)
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val progressLabels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        progressLabels.addView(label("Running diagnostics...", 11f, true))
        stateView = label("This may take a few moments.", 9f)
        progressLabels.addView(stateView)
        progressTop.addView(progressLabels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(9) })
        progressBody.addView(progressTop)

        val progressLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        diagnosticsProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        progressLine.addView(diagnosticsProgress, LinearLayout.LayoutParams(0, dp(6), 1f))
        progressSummaryView = label("0 of 8 complete", 9f).apply { gravity = Gravity.END }
        progressLine.addView(progressSummaryView, LinearLayout.LayoutParams(dp(78), dp(28)))
        progressBody.addView(progressLine)

        runButton = Button(this).apply {
            text = "Run diagnostics"
            isAllCaps = false
            setOnClickListener { runDiagnostic(requestPermission = true) }
        }
        progressBody.addView(runButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
        root.addView(progressCard)

        deviceView = detailSection(root, "Detected device", "No Apple USB device", dp(10), primary, secondary, separator, surface)
        recoveryView = detailSection(root, "Observed boot evidence", "No Recovery snapshot yet", dp(8), primary, secondary, separator, surface, monospace = true)

        root.addView(label("Active development tests", 14f, true).apply { setPadding(0, dp(15), 0, dp(5)) })
        root.addView(label("Boot Diagnostics is the unrestricted development surface. These are the currently implemented hardware tests; future experiments may extend beyond them.", 9f).apply { setPadding(0, 0, 0, dp(7)) })
        root.addView(BootStage2RecoveryButton(this).apply {
            id = R.id.bootStage2RecoveryButton
            text = "Development test: Boot verified M1 Stage 2"
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        root.addView(AutomatedStage2TestButton(this).apply {
            id = R.id.stage2FirmwareBatchTestButton
            text = "Development test: Run current cumulative boundary"
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(5) })

        root.addView(TextView(this).apply {
            id = R.id.operationStatus
            text = "Active test idle"
            textSize = 10f
            setTextColor(secondary)
            setPadding(0, dp(6), 0, dp(3))
        })
        root.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = R.id.operationProgress
            max = 100
            visibility = View.GONE
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5)))

        activeTestLogView = detailSection(root, "Active test log", "No active hardware test output yet", dp(8), primary, secondary, separator, surface, monospace = true)
        activeTestLogView.id = R.id.logView
        testMatrixView = detailSection(root, "Functional test matrix", "No tests have run yet.", dp(8), primary, secondary, separator, surface, monospace = true)
        findingsView = detailSection(root, "Diagnostic findings", "No findings yet", dp(8), primary, secondary, separator, surface)
        timelineView = detailSection(root, "Boot-state timeline", "No events yet", dp(8), primary, secondary, separator, surface, monospace = true)
        logPathView = detailSection(root, "Separate diagnostic logs", "Preparing session folder...", dp(8), primary, secondary, separator, surface, monospace = true)

        root.addView(PrearmedStage1IbecButton(this).apply {
            id = R.id.prearmedStage1IbecButton
            visibility = View.GONE
        })
        root.addView(Stage2FirmwareBatchTestButton(this).apply {
            id = R.id.stage2FirmwareBatchTestDelegateButton
            visibility = View.GONE
        })
        root.addView(TextView(this).apply {
            id = R.id.firmwareTitle
            visibility = View.GONE
            text = FirmwarePreparationStore.get()?.buildId?.let { "Prepared firmware ($it)" } ?: "Prepared firmware unavailable"
        })

        return scroll
    }

    private fun detailSection(
        root: LinearLayout,
        heading: String,
        initial: String,
        topMargin: Int,
        primary: Int,
        secondary: Int,
        separator: Int,
        surface: Int,
        monospace: Boolean = false
    ): TextView {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        root.addView(TextView(this).apply {
            text = heading
            textSize = 12f
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, topMargin, 0, dp(4))
        })
        val card = MaterialCardView(this).apply {
            radius = dp(11).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = separator
            setCardBackgroundColor(surface)
        }
        val value = TextView(this).apply {
            text = initial
            textSize = if (monospace) 9f else 10f
            setTextColor(secondary)
            setTextIsSelectable(true)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            if (monospace) typeface = Typeface.MONOSPACE
        }
        card.addView(value)
        root.addView(card)
        return value
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
        runButton.text = "Running diagnostics..."
        stateView.text = "Running functional tests..."
        diagnosticsProgress.progress = 18
        progressSummaryView.text = "Starting..."
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
                    ),
                    tests = listOf(
                        BootDiagnosticTestResult(
                            id = "diagnostic.engine",
                            title = "Diagnostic engine execution",
                            status = DiagnosticTestStatus.FAILED,
                            detail = error.message ?: error.javaClass.simpleName
                        )
                    )
                )
            }
            runOnUiThread {
                render(snapshot)
                runButton.isEnabled = true
                runButton.text = "Run diagnostics"
            }
        }
    }

    private fun render(snapshot: BootDiagnosticSnapshot) {
        val passed = snapshot.tests.count { it.status == DiagnosticTestStatus.PASSED }
        val failed = snapshot.tests.count { it.status == DiagnosticTestStatus.FAILED }
        val blocked = snapshot.tests.count { it.status == DiagnosticTestStatus.BLOCKED }
        val terminal = snapshot.tests.count {
            it.status == DiagnosticTestStatus.PASSED ||
                it.status == DiagnosticTestStatus.FAILED ||
                it.status == DiagnosticTestStatus.BLOCKED ||
                it.status == DiagnosticTestStatus.NOT_APPLICABLE ||
                it.status == DiagnosticTestStatus.OBSERVED
        }
        val total = snapshot.tests.size.coerceAtLeast(1)
        diagnosticsProgress.progress = ((terminal * 100) / total).coerceIn(0, 100)
        progressSummaryView.text = "$passed passed"
        stateView.text = snapshot.state.name.replace('_', ' ')

        connectionStatusView.renderStatus(snapshot.tests.firstOrNull { it.id == "usb.apple.enumeration" })
        hardwareStatusView.renderStatus(snapshot.tests.firstOrNull { it.id == "boot.identifiers" } ?: snapshot.tests.firstOrNull { it.id == "usb.interface.map" })
        bootStatusView.renderStatus(snapshot.tests.firstOrNull { it.id == "recovery.boot-stage" } ?: snapshot.tests.firstOrNull { it.id == "usb.personality.classification" })
        recoveryStatusView.renderStatus(snapshot.tests.firstOrNull { it.id == "recovery.command-transport" })
        logsStatusView.text = "✓"
        logsStatusView.setTextColor(ContextCompat.getColor(this, R.color.mock_success))

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
        } ?: "No Recovery snapshot for the current personality. USB descriptors and boot identifiers are still tested where available."

        testMatrixView.text = snapshot.tests.joinToString("\n\n") { test ->
            "[${test.status}] ${test.title}\n${test.id}\n${test.detail}"
        }.ifBlank { "No tests produced." }

        findingsView.text = if (snapshot.findings.isEmpty()) {
            "No conclusive finding yet. Connect the affected device in its current boot state and leave this screen open while reproducing the problem."
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
        logPathView.text = "${logger.sessionDirectory.absolutePath}\n\ndiagnostic.log\nusb-events.log\nsummary.txt\n\n$passed passed / $failed failed / $blocked blocked"
    }

    private fun TextView.renderStatus(test: BootDiagnosticTestResult?) {
        val green = ContextCompat.getColor(this@BootDiagnosticsActivity, R.color.mock_success)
        val blue = ContextCompat.getColor(this@BootDiagnosticsActivity, R.color.mock_primary)
        val gray = ContextCompat.getColor(this@BootDiagnosticsActivity, R.color.mock_text_tertiary)
        when (test?.status) {
            DiagnosticTestStatus.PASSED -> { text = "✓"; setTextColor(green) }
            DiagnosticTestStatus.FAILED -> { text = "!"; setTextColor(android.graphics.Color.RED) }
            DiagnosticTestStatus.BLOCKED -> { text = "○"; setTextColor(gray) }
            DiagnosticTestStatus.OBSERVED -> { text = "◔"; setTextColor(blue) }
            DiagnosticTestStatus.NOT_APPLICABLE -> { text = "–"; setTextColor(gray) }
            null -> { text = "○"; setTextColor(gray) }
        }
    }

    @Suppress("unused")
    private fun log(message: String) {
        logger.log("ACTIVE_TEST: $message")
        if (::activeTestLogView.isInitialized) activeTestLogView.append("\n$message")
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
