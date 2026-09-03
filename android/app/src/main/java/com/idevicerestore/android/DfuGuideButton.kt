package com.idevicerestore.android

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton

/** Button that is enabled only while an Apple device is visible in Recovery mode. */
class DfuGuideButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    init {
        setOnClickListener {
            val recovery = currentRecoveryDevice()
            if (recovery == null) {
                isEnabled = false
                return@setOnClickListener
            }
            DfuModeGuide(
                context = context,
                deviceName = recovery.productName ?: "Apple device",
                onRescan = { refreshState() }
            ).show()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshState()
        // MainActivity also rescans on USB attach/detach. This view re-checks whenever
        // the activity/window regains focus after the physical key sequence.
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) refreshState()
    }

    private fun refreshState() {
        isEnabled = currentRecoveryDevice() != null
        text = when {
            currentDfuDevice() != null -> "Device is in DFU mode"
            isEnabled -> "Guide me into DFU mode"
            else -> "DFU guide — connect a device in Recovery"
        }
    }

    private fun currentRecoveryDevice() = usbManager.deviceList.values.firstOrNull {
        it.vendorId == AppleUsb.APPLE_VID && AppleUsb.mode(it) == AppleUsb.Mode.RECOVERY
    }

    private fun currentDfuDevice() = usbManager.deviceList.values.firstOrNull {
        it.vendorId == AppleUsb.APPLE_VID && AppleUsb.mode(it) == AppleUsb.Mode.DFU
    }
}
