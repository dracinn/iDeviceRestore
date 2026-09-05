package com.idevicerestore.android

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatButton

/**
 * Deprecated split-phase iBEC path.
 *
 * Hardware testing proved that Recovery re-enumeration after a standalone 0x41/0 request is not
 * authorization to send iBEC later on a different USB connection. The valid guarded test now keeps
 * upload initialization and bulk payload on one connection in [IbecTransitionButton].
 */
class IbecPostInitUploadButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatButton(context, attrs) {
    init {
        visibility = View.GONE
        isEnabled = false
        text = "Deprecated Post-init iBEC Upload"
        setOnClickListener(null)
    }
}
