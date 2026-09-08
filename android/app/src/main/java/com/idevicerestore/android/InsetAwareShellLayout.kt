package com.idevicerestore.android

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/**
 * Root layout for the mockup-inspired shell.
 *
 * Android 15+ enforces edge-to-edge for modern target SDKs. Applying system-bar insets here keeps
 * the header clear of the status bar and the fixed bottom navigation clear of gesture/3-button
 * navigation without pushing inset handling into MainActivity.
 *
 * This shell also keeps mockup-only controls truthful. Controls that visually exist to match the
 * supplied design but are not backed by application state are rendered non-interactive instead of
 * pretending to change firmware selection, restore safety settings, or diagnostic results.
 */
class InsetAwareShellLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val baseLeft = paddingLeft
    private val baseTop = paddingTop
    private val baseRight = paddingRight
    private val baseBottom = paddingBottom

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
        post { normalizePrototypePresentation(this) }
    }

    private fun normalizePrototypePresentation(view: View) {
        when (view) {
            is EditText -> if (view.hint?.toString() == "Search devices or builds") {
                view.isEnabled = false
                view.hint = "Filtering will be enabled with multi-device catalog support"
                view.alpha = 0.7f
            }

            is CheckBox -> when (view.text?.toString()) {
                "Erase device during restore",
                "Download firmware if absent",
                "Prepare DFU / Recovery automatically when supported" -> {
                    view.isChecked = false
                    view.isEnabled = false
                    if (!view.text.toString().endsWith(" — not yet configurable")) {
                        view.text = "${view.text} — not yet configurable"
                    }
                    view.alpha = 0.7f
                }
            }

            is MaterialButton -> if (view.text?.toString() in FIRMWARE_CATEGORY_LABELS) {
                view.isEnabled = false
                view.alpha = 0.7f
            }

            is ProgressBar -> if (view.id == View.NO_ID && view.max == 100 && view.progress == 25) {
                view.progress = 0
            }

            is TextView -> {
                val current = view.text?.toString().orEmpty()
                view.text = when (current) {
                    "Connected Mac" -> "Connected device"
                    "✓   Device Connection" -> "○   Device Connection — pending"
                    "✓   Hardware Info" -> "○   Hardware Info — pending"
                    "○   Boot Analysis" -> "○   Boot Analysis — run functional tests"
                    "○   Storage Health" -> "○   Storage Health — not yet testable"
                    "○   Recovery Environment" -> "○   Recovery Environment — run functional tests"
                    "○   NVRAM" -> "○   NVRAM — not yet testable"
                    "○   Startup Disk" -> "○   Startup Disk — not yet testable"
                    "○   Logs" -> "○   Logs — captured per diagnostic session"
                    "Detailed boot diagnostics run in their own log window." ->
                        "Run Boot Diagnostics to populate functional pass/fail evidence and separate session logs."
                    else -> current
                }
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                normalizePrototypePresentation(view.getChildAt(index))
            }
        }
    }

    companion object {
        private val FIRMWARE_CATEGORY_LABELS = setOf("Mac", "iPhone", "iPad", "Watch")
    }
}
