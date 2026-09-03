package com.idevicerestore.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton

/**
 * Recovery-only DFU guide launcher that observes USB re-enumeration while the guide is open.
 */
class DfuGuideButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var activeGuide: DfuModeGuide? = null
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    post { refreshAndNotifyGuide() }
                }
            }
        }
    }

    init {
        setOnClickListener {
            val dfu = currentDfuDevice()
            if (dfu != null) {
                refreshState()
                return@setOnClickListener
            }

            val recovery = currentRecoveryDevice()
            if (recovery == null) {
                isEnabled = false
                refreshState()
                return@setOnClickListener
            }

            activeGuide = DfuModeGuide(
                context = context,
                recoveryDevice = recovery,
                onDfuDetected = {
                    refreshState()
                },
                onRescan = {
                    refreshAndNotifyGuide()
                }
            ).also { guide ->
                guide.show()
                guide.onUsbDevicesChanged(usbManager.deviceList.values)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(usbReceiver, filter)
            }
            receiverRegistered = true
        }
        refreshAndNotifyGuide()
    }

    override fun onDetachedFromWindow() {
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
        activeGuide = null
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) refreshAndNotifyGuide()
    }

    private fun refreshAndNotifyGuide() {
        refreshState()
        activeGuide?.onUsbDevicesChanged(usbManager.deviceList.values)
    }

    private fun refreshState() {
        val dfu = currentDfuDevice()
        val recovery = currentRecoveryDevice()
        isEnabled = recovery != null && dfu == null
        text = when {
            dfu != null -> "Device is in DFU mode"
            recovery != null -> "Guide me into DFU mode"
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
