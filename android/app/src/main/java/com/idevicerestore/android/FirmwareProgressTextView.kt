package com.idevicerestore.android

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/** Prefixes firmware transfer detail with the current progress percentage. */
class FirmwareProgressTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {
    private var rawText: CharSequence = text
    private var internalUpdate = false

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!internalUpdate) rawText = text ?: ""
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { render() }
    }

    private fun render() {
        val detail = rawText.toString().trim()
        val progress = rootView.findViewById<android.widget.ProgressBar?>(R.id.firmwareProgress)
        val rendered = if (detail.isEmpty()) {
            ""
        } else if (progress != null && progress.max > 0) {
            val percent = (progress.progress.toDouble() * 100.0 / progress.max.toDouble()).coerceIn(0.0, 100.0)
            "%.1f%% · %s".format(percent, detail)
        } else {
            detail
        }
        if (rendered != super.getText().toString()) {
            internalUpdate = true
            try {
                super.setText(rendered, BufferType.NORMAL)
            } finally {
                internalUpdate = false
            }
        }
    }
}
