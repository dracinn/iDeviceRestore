package com.idevicerestore.android

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/** Display-only connected-device status that appends the exact observed boot state. */
class BootStateStatusTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {
    private var baseText: CharSequence = text
    private var internalUpdate = false

    private val refresh = object : Runnable {
        override fun run() {
            refreshBootState()
            if (isAttachedToWindow) postDelayed(this, REFRESH_MS)
        }
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!internalUpdate) baseText = text ?: ""
        super.setText(text, type)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    private fun refreshBootState() {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usb.deviceList.values.firstOrNull { it.vendorId == AppleUsb.APPLE_VID }
        val suffix = when {
            device == null -> null
            AppleUsb.mode(device) == AppleUsb.Mode.DFU -> "DFU"
            AppleUsb.mode(device) == AppleUsb.Mode.WTF -> "WTF"
            AppleUsb.mode(device) == AppleUsb.Mode.RECOVERY -> {
                val evidence = RestorePreflightEvidenceStore.snapshot().recovery
                    ?.takeIf { it.deviceName == device.deviceName }
                when (evidence?.bootStage) {
                    "1" -> "Recovery · Stage 1${evidence.buildVersion?.let { " · $it" } ?: ""}"
                    "2" -> "Recovery · Stage 2${evidence.buildVersion?.let { " · $it" } ?: ""}"
                    null -> "Recovery · boot stage probing"
                    else -> "Recovery · Stage ${evidence.bootStage}${evidence.buildVersion?.let { " · $it" } ?: ""}"
                }
            }
            else -> AppleUsb.mode(device).name
        }

        val cleanBase = baseText.toString()
            .replace(Regex("\\s+—\\s+(?:DFU|RECOVERY|WTF|APPLE_OTHER)$", RegexOption.IGNORE_CASE), "")
            .trim()
        val rendered = if (suffix == null || cleanBase.isEmpty()) {
            if (suffix == null) cleanBase else suffix
        } else {
            "$cleanBase — $suffix"
        }
        if (rendered != text.toString()) {
            internalUpdate = true
            try {
                super.setText(rendered, BufferType.NORMAL)
            } finally {
                internalUpdate = false
            }
        }
    }

    companion object {
        private const val REFRESH_MS = 500L
    }
}
