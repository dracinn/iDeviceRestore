package com.idevicerestore.android

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * UI-only preview host used by the four preview product flavors.
 * No restore, firmware, USB, DFU, network, or diagnostic operation is invoked here.
 */
class PreviewActivity : AppCompatActivity() {
    private val blue = Color.rgb(10, 104, 255)
    private val muted = Color.rgb(88, 101, 120)
    private val cardStroke = Color.rgb(226, 231, 239)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "iDeviceRestore Preview"
        setContentView(buildRoot(BuildConfig.PREVIEW_SCREEN))
    }

    private fun buildRoot(screen: String): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        outer.addView(header(screen))
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        when (screen) {
            "firmware" -> firmware(content)
            "restore" -> restore(content)
            "diagnostics" -> diagnostics(content)
            else -> home(content)
        }
        outer.addView(bottomNav(screen))
        return outer
    }

    private fun header(screen: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(14))
        addView(text(if (screen == "home") "iDeviceRestore" else when (screen) {
            "firmware" -> "Firmware Catalog"
            "restore" -> "Restore Device"
            else -> "Device Diagnostics"
        }, 24f, Color.BLACK, true))
        addView(text(when (screen) {
            "firmware" -> "Browse, search, and download Apple firmware."
            "restore" -> "Select firmware and options to restore your device."
            "diagnostics" -> "Run tests and view detailed device information."
            else -> "Restore • Update • Recover"
        }, 13f, muted))
    }

    private fun home(parent: LinearLayout) {
        parent.addView(card {
            addView(text("MacBook Air (M1, 2020)", 22f, Color.BLACK, true))
            addView(text("MacBookAir10,1", 14f, muted))
            addView(text("●  Recovery Mode", 14f, Color.rgb(24, 170, 78)))
            addView(spacer(10))
            addView(text("ECID        0x1234567890ABCDEF\nCPID        0x8103\nModel       MacBookAir10,1", 14f, muted))
            addView(buttonRow("View Details", "Change Mode"))
        })
        val actions = listOf(
            "Download Firmware" to "Get the latest or choose from signed releases",
            "Restore Device" to "Erase and install firmware",
            "Update Device" to "Install the latest version",
            "Run Diagnostics" to "Check device health and troubleshoot"
        )
        actions.forEach { (title, body) -> parent.addView(actionCard(title, body)) }
        parent.addView(card {
            addView(text("●  Device Connected", 14f, Color.rgb(24, 170, 78), true))
            addView(text("MacBook Air (M1, 2020) • Recovery Mode", 13f, muted))
        })
    }

    private fun firmware(parent: LinearLayout) {
        val field = TextInputLayout(this).apply { hint = "Search by device or version…" }
        field.addView(TextInputEditText(this))
        parent.addView(field, marginParams())
        parent.addView(card {
            addView(text("MacBook Air (M1, 2020)        All Versions        All Build Types", 14f, muted))
        })
        listOf(
            "26.6.2 (25G83)" to "2026-08-21   Signed   12.4 GB",
            "26.6.1 (25G74)" to "2026-07-28   Signed   12.4 GB",
            "26.6 (25G60)" to "2026-07-12   Signed   12.3 GB",
            "26.5 (25F66)" to "2026-06-01   Signed   12.3 GB",
            "26.4 (25E42)" to "2026-04-20   Signed   12.2 GB",
            "26.3 (25D21)" to "2026-03-15   Signed   12.2 GB"
        ).forEach { (title, meta) ->
            parent.addView(card {
                addView(text(title, 16f, Color.BLACK, true))
                addView(text(meta, 13f, muted))
                addView(primaryButton("Download"))
            })
        }
        parent.addView(card { addView(text("📁  Download Location\n/storage/emulated/0/iDeviceRestore/Firmware/…", 13f, muted)) })
    }

    private fun restore(parent: LinearLayout) {
        parent.addView(card {
            addView(text("1  Select Firmware   2  Options   3  Prepare   4  Restore", 14f, blue, true))
        })
        parent.addView(card {
            addView(text("Selected Firmware", 16f, Color.BLACK, true))
            addView(text("macOS 26.6.2 (25G83)\nMacBookAir10,1\n12.4 GB", 14f, muted))
        })
        parent.addView(card {
            addView(text("Restore Options", 16f, Color.BLACK, true))
            addView(check("Erase all content (clean install)", true))
            addView(check("Update to latest compatible firmware (if available)", false))
            addView(check("Create a log file", true))
            addView(check("Verify firmware before restoring", true))
        })
        parent.addView(card { addView(text("ⓘ  Your device will be restored to macOS 26.6.2 (25G83). Make sure the device is connected and in Recovery mode before continuing.", 13f, muted)) })
        parent.addView(buttonRow("Back", "Continue"))
    }

    private fun diagnostics(parent: LinearLayout) {
        parent.addView(card { addView(text("Device Info     USB/Boot     Logs     Health Check", 14f, blue, true)) })
        parent.addView(card {
            addView(text("MacBook Air (M1, 2020)", 20f, Color.BLACK, true))
            addView(text("MacBookAir10,1\n● Recovery Mode", 14f, muted))
            addView(text("ECID        0x1234567890ABCDEF\nCPID        0x8103\nBDID        0x0A\nApNonce     0x1122334455667788\nSepNonce    0x8877665544332211\nBuild       mBoot-20457.1.29", 13f, muted))
        })
        parent.addView(card {
            addView(text("USB Information", 16f, Color.BLACK, true))
            addView(text("Vendor ID     0x05ac (Apple Inc.)\nProduct ID    0x1281 (Recovery Mode)\nInterface     3\nTransport     USB 2.0 (High Speed)\nSerial        0000000000000000", 13f, muted))
        })
        parent.addView(card {
            addView(text("Boot Stage", 16f, Color.BLACK, true))
            addView(text("Current Stage    iBSS\nBuild            mBoot-20457.1.29\nTransition Time  824 ms\nStatus           ● Ready", 13f, muted))
        })
        parent.addView(primaryButton("Run Full Diagnostic Test"))
    }

    private fun bottomNav(screen: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, 0)
        val items = listOf("Home", "Devices", "Firmware", "Restore", "More")
        items.forEach { label ->
            val selected = (screen == "home" && label == "Home") ||
                (screen == "firmware" && label == "Firmware") ||
                (screen == "restore" && label == "Restore") ||
                (screen == "diagnostics" && label == "More")
            addView(text(label, 12f, if (selected) blue else muted, selected), LinearLayout.LayoutParams(0, dp(44), 1f))
        }
    }

    private fun actionCard(title: String, body: String) = card {
        addView(text(title, 17f, Color.BLACK, true))
        addView(text(body, 13f, muted))
    }

    private fun card(block: LinearLayout.() -> Unit): MaterialCardView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            block()
        }
        return MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            strokeWidth = dp(1)
            strokeColor = cardStroke
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
            addView(inner)
            layoutParams = marginParams()
        }
    }

    private fun buttonRow(left: String, right: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(MaterialButton(this@PreviewActivity).apply { text = left }, LinearLayout.LayoutParams(0, dp(52), 1f))
        addView(spacer(8))
        addView(primaryButton(right), LinearLayout.LayoutParams(0, dp(52), 1f))
    }

    private fun primaryButton(label: String) = MaterialButton(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(blue)
        isAllCaps = false
        isEnabled = true
        setOnClickListener { /* preview intentionally inert */ }
    }

    private fun check(label: String, checked: Boolean) = MaterialCheckBox(this).apply {
        text = label
        isChecked = checked
        isEnabled = false
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun spacer(dp: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(this@PreviewActivity.dp(dp), this@PreviewActivity.dp(dp)) }
    private fun marginParams() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
