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
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/**
 * Root layout for the reference-matched Android shell.
 *
 * System-bar insets are applied here so the visible hierarchy can stay faithful to the supplied
 * mockup. Presentation-only controls may be non-interactive, but they intentionally keep their
 * normal visual opacity so disabled implementation state does not distort the reference design.
 */
class InsetAwareShellLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val baseLeft = paddingLeft
    private val baseTop = paddingTop
    private val baseRight = paddingRight
    private val baseBottom = paddingBottom
    private var homeModeBadge: TextView? = null

    private val referenceStateMirror = object : Runnable {
        override fun run() {
            mirrorReferenceState()
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
        post {
            normalizePrototypePresentation(this)
            applyNightAwareReferenceSurfaces()
            referenceStateMirror.run()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(referenceStateMirror)
        super.onDetachedFromWindow()
    }

    private fun mirrorReferenceState() {
        val status = findViewById<TextView?>(R.id.status)?.text?.toString().orEmpty()
        val titleView = findViewById<TextView?>(R.id.deviceDisplayName)
        val identifierView = findViewById<TextView?>(R.id.deviceIdentifierText)

        val primary = status.substringBefore(" — ").trim()
        val match = Regex("^(.*) \\(([^()]+)\\)$").matchEntire(primary)
        if (match != null) {
            titleView?.text = match.groupValues[1]
            identifierView?.text = match.groupValues[2]
        } else if (status.startsWith("No Apple USB", ignoreCase = true)) {
            titleView?.text = "No Apple device connected"
            identifierView?.text = "Connect a device in Recovery or DFU"
        }

        homeModeBadge?.let { badge ->
            when {
                status.contains("RECOVERY", ignoreCase = true) -> {
                    badge.text = "●  Recovery Mode"
                    badge.setTextColor(ContextCompat.getColor(context, R.color.mock_success))
                    badge.setBackgroundResource(R.drawable.mock_reference_status_pill)
                }
                status.contains("DFU", ignoreCase = true) -> {
                    badge.text = "●  DFU Mode"
                    badge.setTextColor(ContextCompat.getColor(context, R.color.mock_primary))
                    badge.setBackgroundResource(R.drawable.mock_reference_card)
                }
                status.startsWith("No Apple USB", ignoreCase = true) || status.isBlank() -> {
                    badge.text = "○  No Device"
                    badge.setTextColor(ContextCompat.getColor(context, R.color.mock_text_secondary))
                    badge.setBackgroundResource(R.drawable.mock_reference_card)
                }
                else -> {
                    badge.text = "●  Apple Device"
                    badge.setTextColor(ContextCompat.getColor(context, R.color.mock_primary))
                    badge.setBackgroundResource(R.drawable.mock_reference_card)
                }
            }
        }

        val firmwareTitle = findViewById<TextView?>(R.id.firmwareTitle)?.text?.toString().orEmpty()
        val homeFirmware = findViewById<TextView?>(R.id.homeFirmwareSummary)
        if (firmwareTitle.startsWith("Firmware ")) {
            homeFirmware?.text = firmwareTitle.removePrefix("Firmware ")
        } else if (firmwareTitle.contains("(")) {
            homeFirmware?.text = firmwareTitle
        }
    }

    private fun applyNightAwareReferenceSurfaces() {
        val connectedLabel = findTextView(this) { it.text?.toString() == "Connected Mac" }
        val selectedRow = connectedLabel?.parent?.parent as? View
        selectedRow?.setBackgroundColor(ContextCompat.getColor(context, R.color.mock_selected_row))
    }

    private fun normalizePrototypePresentation(view: View) {
        when (view) {
            is EditText -> if (view.hint?.toString() == "Search devices or builds") {
                view.isEnabled = false
                view.hint = "Search devices (e.g. MacBookAir10,1)"
                view.alpha = 1f
            }

            is SwitchCompat -> when (view.text?.toString()) {
                "Automatically detect devices",
                "Check for app updates at launch",
                "Use verbose logging",
                "Organize firmware by device" -> {
                    view.isEnabled = false
                    view.alpha = 1f
                }
            }

            is CheckBox -> when (view.text?.toString()) {
                "Erase device during restore",
                "Download firmware if absent",
                "Prepare DFU / Recovery automatically when supported" -> {
                    view.isChecked = false
                    view.isEnabled = false
                    view.alpha = 1f
                }
            }

            is MaterialButton -> if (view.text?.toString() in FIRMWARE_CATEGORY_LABELS) {
                view.isEnabled = false
                view.alpha = 1f
            }

            is ProgressBar -> if (view.id == View.NO_ID && view.max == 100 && view.progress == 25) {
                view.progress = 0
            }

            is TextView -> if (view.text?.toString() == "●  Recovery Mode" && homeModeBadge == null) {
                homeModeBadge = view
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                normalizePrototypePresentation(view.getChildAt(index))
            }
        }
    }

    private fun findTextView(root: View, predicate: (TextView) -> Boolean): TextView? {
        if (root is TextView && predicate(root)) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findTextView(root.getChildAt(index), predicate)?.let { return it }
            }
        }
        return null
    }

    companion object {
        private val FIRMWARE_CATEGORY_LABELS = setOf("Mac", "iPhone", "iPad", "Watch")
    }
}
