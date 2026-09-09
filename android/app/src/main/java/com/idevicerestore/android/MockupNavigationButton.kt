package com.idevicerestore.android

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/** Presentation-only routing for the reference-matched shell. */
class MockupNavigationButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    init {
        setOnClickListener { route(tag?.toString()) }
    }

    private fun route(action: String?) {
        when (action) {
            ACTION_HOME -> showScreen(R.id.screenHome, ACTION_HOME)
            ACTION_FIRMWARE, ACTION_UPDATE -> {
                showScreen(R.id.screenFirmware, ACTION_FIRMWARE)
                ConnectedFirmwareBrowser.render(rootView, context)
            }
            ACTION_DEVICES -> showScreen(R.id.screenDevices, ACTION_DEVICES)
            ACTION_TOOLS -> showScreen(R.id.screenTools, ACTION_TOOLS)
            ACTION_DIAGNOSTICS, ACTION_BOOT_DIAGNOSTICS -> openBootDiagnostics()
            ACTION_RESTORE -> Unit
        }
    }

    private fun openBootDiagnostics() {
        AndroidUiBridge.activity(context)?.startActivity(
            Intent(context, BootDiagnosticsActivity::class.java)
        )
    }

    private fun showScreen(targetId: Int, selectedNavAction: String?) {
        val root = rootView
        SCREEN_IDS.forEach { id ->
            root.findViewById<View?>(id)?.visibility = if (id == targetId) View.VISIBLE else View.GONE
        }
        root.findViewById<View?>(targetId)?.let { target ->
            target.post { target.scrollTo(0, 0) }
        }
        updateBottomNavigation(selectedNavAction)
    }

    private fun updateBottomNavigation(selectedAction: String?) {
        val primary = ContextCompat.getColor(context, R.color.mock_primary)
        val secondary = ContextCompat.getColor(context, R.color.mock_text_secondary)
        NAV_BUTTON_IDS.forEach { id ->
            val button = rootView.findViewById<MaterialButton?>(id) ?: return@forEach
            val selected = button.tag?.toString() == selectedAction
            val tint = ColorStateList.valueOf(if (selected) primary else secondary)
            button.setTextColor(tint)
            button.iconTint = tint
            button.isSelected = selected
        }
    }

    companion object {
        private const val ACTION_HOME = "home"
        private const val ACTION_DEVICES = "devices"
        private const val ACTION_FIRMWARE = "firmware"
        private const val ACTION_UPDATE = "update"
        private const val ACTION_TOOLS = "tools"
        private const val ACTION_RESTORE = "restore"
        private const val ACTION_DIAGNOSTICS = "diagnostics"
        private const val ACTION_BOOT_DIAGNOSTICS = "boot_diagnostics"

        private val SCREEN_IDS = intArrayOf(
            R.id.screenHome,
            R.id.screenFirmware,
            R.id.screenDevices,
            R.id.screenTools
        )
        private val NAV_BUTTON_IDS = intArrayOf(
            R.id.navHomeButton,
            R.id.navFirmwareButton,
            R.id.navDevicesButton,
            R.id.navToolsButton
        )
    }
}
