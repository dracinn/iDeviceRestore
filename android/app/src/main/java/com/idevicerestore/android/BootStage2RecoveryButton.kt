package com.idevicerestore.android

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton

/**
 * User-facing Stage-2 boot action. The proven PrearmedStage1IbecButton remains the implementation
 * backing this control, but its older diagnostic wording is kept out of the primary UI.
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
        isEnabled = false
        setOnClickListener {
            rootView.findViewById<PrearmedStage1IbecButton?>(R.id.prearmedStage1IbecButton)?.performClick()
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
        private const val LABEL = "Boot Stage 2 Recovery"
    }
}
