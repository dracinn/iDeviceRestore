package com.idevicerestore.android

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatButton

/**
 * Diagnostics-only Stage-2 boot action. The proven PrearmedStage1IbecButton remains the
 * implementation backing this control, but this button must never be treated as a prerequisite
 * for the cumulative hardware test. Run Current Stage-2 Test owns the automated DFU-to-current
 * boundary path.
 *
 * This control is visible only inside BootDiagnosticsActivity. It stops at the bounded Stage-2
 * proof and performs no post-test setenv/saveenv repair; the cumulative test owns its own
 * auto-boot normalization and is therefore self-sufficient.
 */
class BootStage2RecoveryButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatButton(context, attrs) {

    private val refresh = object : Runnable {
        override fun run() {
            val delegate = rootView.findViewById<PrearmedStage1IbecButton?>(R.id.prearmedStage1IbecButton)
            isEnabled = delegate?.isEnabled == true
            if (isAttachedToWindow) postDelayed(this, REFRESH_MS)
        }
    }

    init {
        text = LABEL
        contentDescription = "Diagnostics only: boot Stage 2 Recovery"
        isEnabled = false
        setOnClickListener {
            AndroidUiBridge.activity(context)?.let { activity ->
                AndroidUiBridge.log(
                    activity,
                    "Stage-2 development test: bounded DFU → Stage-2 proof only; no post-test environment mutation; cumulative test remains self-normalizing"
                )
            }
            rootView.findViewById<PrearmedStage1IbecButton?>(R.id.prearmedStage1IbecButton)
                ?.performClick()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val diagnosticsHost = AndroidUiBridge.activity(context) is BootDiagnosticsActivity
        visibility = if (diagnosticsHost) View.VISIBLE else View.GONE
        if (diagnosticsHost) post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val REFRESH_MS = 500L
        private const val LABEL = "Development test: Boot verified M1 Stage 2"
    }
}
