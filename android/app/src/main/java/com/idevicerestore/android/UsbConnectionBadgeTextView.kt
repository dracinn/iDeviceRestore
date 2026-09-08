package com.idevicerestore.android

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat

/** Small live badge that reflects whether an Apple USB device is currently enumerated. */
class UsbConnectionBadgeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {
    private val refresh = object : Runnable {
        override fun run() {
            val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val connected = usb.deviceList.values.any { it.vendorId == AppleUsb.APPLE_VID }
            text = if (connected) "●  Live USB" else "○  No USB"
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (connected) R.color.mock_success else R.color.mock_text_secondary
                )
            )
            if (isAttachedToWindow) postDelayed(this, REFRESH_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val REFRESH_MS = 500L
    }
}
