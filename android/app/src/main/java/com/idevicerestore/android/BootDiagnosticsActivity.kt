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
import android.widget.ProgressBar
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
    private lateinit var testMatrixView: TextView
    private lateinit var findingsView: TextView
    private lateinit var timelineView: TextView
    private lateinit var logPathView: TextView
    private lateinit var activeTestLogView: TextView
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

        logger.log("Boot diagnostic development session started")
        logger.log("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        logger.log("Policy: Boot Diagnostics is the unrestricted development and hardware-test surface; only verified functions are promoted to the main app")
        logger.log("Individual development tests may define their own prerequisites, confirmations, and current stop boundaries while they are being proven")
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
            text = "Unrestricted development and hardware-validation lab. New device support, protocol work, restore behavior, mutations, transport experiments, and bug fixes are developed here before verified functionality is promoted into the normal iDeviceRestore interface."
            textSize = 14f
            setPadding(0, dp(4), 0, dp(14))
        })

        root.addView(TextView(this).apply {
            text = "CURRENT TEST SCOPE"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Boot Diagnostics itself has no product-level development restriction. The entries below describe only what the currently implemented tests do today. Future experimental tests may go beyond these boundaries while they are being developed here.\n\n" +
                "• Functional matrix: currently read-only USB/Recovery observation.\n" +
                "• M1 Stage-2 development test: currently uses the proven CPID=8103 path and stops at verified Stage 2.\n" +
                "• Current cumulative Stage-2 test: currently reaches the proven restore-entry boundary and stops after the first fresh post-Recovery Apple USB enumeration.\n" +
                "• Future-device and restore-development tests may add new active behavior here first, with their actual prerequisites, mutations, and observed results logged explicitly."
            textSize = 12f
            setPadding(0, 0, 0, dp(14))
        })

        stateView = section(root, "Current state", "Waiting for scan", 20f)
        deviceView = section(root, "Detected device", "No Apple USB device", 14f)
        recoveryView = section(root, "Observed boot evidence", "No Recovery snapshot yet", 13f, monospace = true)

        runButton = Button(this).apply {
            text = "Run current functional test matrix"
            setOnClickListener { runDiagnostic(requestPermission = true) }
        }
        root.addView(runButton)

        testMatrixView = section(
            root,
            "Functional test matrix",
            "No tests have run yet.",
            13f,
            monospace = true
        )
        findingsView = section(root, "Diagnostic findings", "No findings yet", 14f)
        timelineView = section(root, "Boot-state timeline", "No events yet", 12f, monospace = true)

        root.addView(TextView(this).apply {
            text = "Active development tests"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "All experimental hardware work belongs here until it is verified. Each control should state what it currently does, what prerequisites it needs, and what evidence it produced; those are test properties, not restrictions on Boot Diagnostics as a development surface."
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        })

        root.addView(BootStage2RecoveryButton(this).apply {
            id = R.id.bootStage2RecoveryButton
            text = "Development test: Boot verified M1 Stage 2"
        })
        root.addView(AutomatedStage2TestButton(this).apply {
            id = R.id.stage2FirmwareBatchTestButton
            text = "Development test: Run current cumulative boundary"
        })

        val operationStatus = TextView(this).apply {
            id = R.id.operationStatus
            text = "Active test idle"
            textSize = 12f
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(operationStatus)
        root.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = R.id.operationProgress
            max = 100
            visibility = View.GONE
        })

        activeTestLogView = section(root, "Active test log", "No active hardware test output yet", 11f, monospace = true)
        activeTestLogView.id = R.id.logView

        // Hidden delegates keep the existing proven state machines intact while moving their
        // user-facing controls into Boot Diagnostics. They are prerequisites, not extra actions.
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

        logPathView = section(root, "Separate diagnostic logs", "Preparing session folder…", 12f, monospace = true)

        root.addView(TextView(this).apply {
            text = "Status meanings: PASSED confirms an observed capability; FAILED means an expected check for the current personality did not work; BLOCKED means a prerequisite is missing; OBSERVED records useful evidence without declaring success/failure; NOT_APPLICABLE prevents device-specific knowledge from being misapplied to other hardware."
            textSize = 12f
            setPadding(0, dp(16), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "Promotion rule: Boot Diagnostics may contain unverified and experimental functionality. A feature belongs in the main app only after its supported scope, repeatable hardware proof, failure behavior, logging, and intended user-facing behavior are established."
            textSize = 12f
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
        stateView.text = "Running functional tests…"
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
            }
        }
    }

    private fun render(snapshot: BootDiagnosticSnapshot) {
        val passed = snapshot.tests.count { it.status == DiagnosticTestStatus.PASSED }
        val failed = snapshot.tests.count { it.status == DiagnosticTestStatus.FAILED }
        val blocked = snapshot.tests.count { it.status == DiagnosticTestStatus.BLOCKED }
        stateView.text = buildString {
            append(snapshot.state.name.replace('_', ' '))
            if (snapshot.tests.isNotEmpty()) append("  •  $passed passed / $failed failed / $blocked blocked")
        }
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
        logPathView.text = buildString {
            append("Session folder\n${logger.sessionDirectory.absolutePath}\n\n")
            append("diagnostic.log\nusb-events.log\nsummary.txt")
        }
    }

    @Suppress("unused")
    private fun log(message: String) {
        logger.log("ACTIVE_TEST: $message")
        if (::activeTestLogView.isInitialized) {
            activeTestLogView.append("\n$message")
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
