package com.idevicerestore.android

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Root for the approved mockup-first Android UI.
 *
 * Existing transport/firmware state is mirrored into the visible mockup fields. The functional
 * delegate controls remain hidden, so changes to app behavior cannot silently reshape the design.
 */
class InsetAwareShellLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val baseLeft = paddingLeft
    private val baseTop = paddingTop
    private val baseRight = paddingRight
    private val baseBottom = paddingBottom

    private val stateMirror = object : Runnable {
        override fun run() {
            mirrorState()
            if (isAttachedToWindow) postDelayed(this, 350L)
        }
    }

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
        post { stateMirror.run() }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(stateMirror)
        super.onDetachedFromWindow()
    }

    private fun mirrorState() {
        val status = findViewById<TextView?>(R.id.status)?.text?.toString().orEmpty()
        val primary = status.substringBefore(" — ").trim()
        val nameMatch = Regex("^(.*) \\(([^()]+)\\)$").matchEntire(primary)

        val deviceName = when {
            nameMatch != null -> nameMatch.groupValues[1]
            status.startsWith("No Apple USB", ignoreCase = true) || status.isBlank() -> "No Apple device connected"
            else -> findViewById<TextView?>(R.id.deviceDisplayName)?.text?.toString().orEmpty()
                .ifBlank { "Connected Apple device" }
        }
        val identifier = when {
            nameMatch != null -> nameMatch.groupValues[2]
            status.startsWith("No Apple USB", ignoreCase = true) || status.isBlank() -> "Connect a device in Recovery or DFU"
            else -> findViewById<TextView?>(R.id.deviceIdentifierText)?.text?.toString().orEmpty()
                .ifBlank { "Apple device" }
        }

        setText(R.id.deviceDisplayName, deviceName)
        setText(R.id.deviceIdentifierText, identifier)
        setText(R.id.diagnosticsDeviceName, deviceName)
        setText(R.id.diagnosticsIdentifier, identifier)
        setText(R.id.diagnosticsProductType, identifier)
        setText(R.id.homeModelValue, identifier)
        setText(R.id.firmwareDeviceSelectorLabel, deviceName)

        val mode = when {
            status.contains("RECOVERY", ignoreCase = true) -> "Recovery Mode"
            status.contains("DFU", ignoreCase = true) -> "DFU Mode"
            status.startsWith("No Apple USB", ignoreCase = true) || status.isBlank() -> "No Device"
            else -> "Apple Device"
        }
        val modeGlyph = if (mode == "No Device") "○" else "●"
        setText(R.id.homeModeBadge, "$modeGlyph  $mode")
        setText(R.id.diagnosticsMode, "$modeGlyph  $mode")
        setText(R.id.homeConnectionSummary, "$deviceName • $mode")

        extractHex(status, "ECID")?.let {
            setText(R.id.homeEcidValue, it)
            setText(R.id.diagnosticsEcid, it)
        }
        extractHex(status, "CPID")?.let {
            setText(R.id.homeCpidValue, it)
            setText(R.id.diagnosticsCpid, it)
        }

        val firmwareTitle = findViewById<TextView?>(R.id.firmwareTitle)?.text?.toString().orEmpty()
        val firmwareSummary = when {
            firmwareTitle.startsWith("Firmware ") -> firmwareTitle.removePrefix("Firmware ")
            firmwareTitle.contains("(") -> firmwareTitle
            else -> null
        }
        if (firmwareSummary != null) {
            setText(R.id.firmwarePrimaryVersion, firmwareSummary)
            setText(R.id.restoreFirmwareTitle, "macOS $firmwareSummary")
            setText(R.id.restoreFirmwareIdentifier, identifier)
            setText(R.id.restoreInfoText, "Your device will be restored to macOS $firmwareSummary.\nMake sure your device is connected and in Recovery mode before continuing.")
            setText(R.id.homeFirmwareSummary, firmwareSummary)
        }

        val activityLog = findViewById<TextView?>(R.id.logView)?.text
        val probeLog = findViewById<TextView?>(R.id.probeLogView)?.text
        if (activityLog != null || probeLog != null) {
            SessionLogSnapshotStore.update(activityLog, probeLog)
        }
    }

    private fun setText(id: Int, value: String) {
        findViewById<TextView?>(id)?.text = value
    }

    private fun extractHex(text: String, label: String): String? {
        val match = Regex("(?i)\\b$label\\b\\s*[:=]?\\s*(0x)?([0-9a-f]{3,})").find(text) ?: return null
        val prefix = match.groupValues[1]
        val digits = match.groupValues[2]
        return if (prefix.isNotEmpty()) "0x$digits" else "0x$digits"
    }
}
