package com.idevicerestore.android

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/** Interaction layer for the approved Android mockup. */
class MockupNavigationButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    init { setOnClickListener { route(tag?.toString()) } }

    private fun route(action: String?) {
        when (action) {
            ACTION_HOME -> showScreen(R.id.screenHome, ACTION_HOME)
            ACTION_FIRMWARE, ACTION_UPDATE -> showScreen(R.id.screenFirmware, ACTION_FIRMWARE)
            ACTION_RESTORE -> showScreen(R.id.screenRestore, ACTION_RESTORE)
            ACTION_DEVICES -> showScreen(R.id.screenDiagnostics, ACTION_DEVICES)
            ACTION_DIAGNOSTICS -> showScreen(R.id.screenDiagnostics, null)
            ACTION_MORE, ACTION_TOOLS -> showScreen(R.id.screenTools, ACTION_MORE)
            ACTION_RUN_DIAGNOSTICS, ACTION_BOOT_DIAGNOSTICS -> openBootDiagnostics()
            ACTION_CHANGE_MODE -> delegateClick(R.id.dfuGuideDelegateButton, "Connect a device in Recovery to change mode")
            ACTION_FIRMWARE_SELECT -> delegateClick(R.id.selectFirmwareButton, "Identify a connected device before selecting firmware")
            ACTION_FIRMWARE_ACTION -> runFirmwareAction()
            ACTION_RESTORE_CONTINUE -> continueRestoreFlow()
        }
    }

    private fun runFirmwareAction() {
        val download = rootView.findViewById<View?>(R.id.downloadFirmwareButton)
        if (download?.isEnabled == true) download.performClick()
        else delegateClick(R.id.selectFirmwareButton, "Choose signed firmware before downloading")
    }

    private fun continueRestoreFlow() {
        val restore = rootView.findViewById<View?>(R.id.startRestoreDelegateButton)
        if (restore?.isEnabled == true) { restore.performClick(); return }
        val selector = rootView.findViewById<View?>(R.id.selectFirmwareButton)
        if (selector?.isEnabled == true) { selector.performClick(); return }
        Toast.makeText(context, "Connect and identify a supported Apple device before continuing", Toast.LENGTH_SHORT).show()
    }

    private fun delegateClick(viewId: Int, unavailableMessage: String) {
        val delegate = rootView.findViewById<View?>(viewId)
        if (delegate?.isEnabled == true) delegate.performClick()
        else Toast.makeText(context, unavailableMessage, Toast.LENGTH_SHORT).show()
    }

    private fun openBootDiagnostics() {
        AndroidUiBridge.activity(context)?.startActivity(Intent(context, BootDiagnosticsActivity::class.java))
    }

    private fun showScreen(targetId: Int, selectedNavAction: String?) {
        val root = rootView
        if (targetId == R.id.screenFirmware) installFirmwareReference(root.findViewById(targetId))
        SCREEN_IDS.forEach { id -> root.findViewById<View?>(id)?.visibility = if (id == targetId) View.VISIBLE else View.GONE }
        root.findViewById<View?>(targetId)?.let { target -> target.post { target.scrollTo(0, 0) } }
        updateBottomNavigation(selectedNavAction)
    }

    /**
     * Keeps the legacy functional controls in the hierarchy as invisible delegates while the
     * approved FirmwareReferenceView owns every visible pixel of the firmware destination.
     */
    private fun installFirmwareReference(screen: ViewGroup?) {
        if (screen == null || screen.getTag(R.id.screenFirmware) == "reference-installed") return
        val legacy = if (screen.childCount > 0) screen.getChildAt(0) else null
        if (legacy != null) screen.removeView(legacy)
        val wrapper = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (legacy != null) {
            legacy.visibility = View.GONE
            wrapper.addView(legacy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        wrapper.addView(FirmwareReferenceView(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        screen.addView(wrapper, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        screen.setTag(R.id.screenFirmware, "reference-installed")
    }

    private fun updateBottomNavigation(selectedAction: String?) {
        val primary = ContextCompat.getColor(context, R.color.mock_primary)
        val secondary = ContextCompat.getColor(context, R.color.mock_text_secondary)
        NAV_BUTTON_IDS.forEach { id ->
            val button = rootView.findViewById<MaterialButton?>(id) ?: return@forEach
            val selected = selectedAction != null && button.tag?.toString() == selectedAction
            val tint = ColorStateList.valueOf(if (selected) primary else secondary)
            button.setTextColor(tint); button.iconTint = tint; button.isSelected = selected
        }
    }

    companion object {
        private const val ACTION_HOME = "home"
        private const val ACTION_DEVICES = "devices"
        private const val ACTION_FIRMWARE = "firmware"
        private const val ACTION_UPDATE = "update"
        private const val ACTION_RESTORE = "restore"
        private const val ACTION_DIAGNOSTICS = "diagnostics"
        private const val ACTION_MORE = "more"
        private const val ACTION_TOOLS = "tools"
        private const val ACTION_BOOT_DIAGNOSTICS = "boot_diagnostics"
        private const val ACTION_RUN_DIAGNOSTICS = "run_diagnostics"
        private const val ACTION_CHANGE_MODE = "change_mode"
        private const val ACTION_FIRMWARE_SELECT = "firmware_select"
        private const val ACTION_FIRMWARE_ACTION = "firmware_action"
        private const val ACTION_RESTORE_CONTINUE = "restore_continue"
        private val SCREEN_IDS = intArrayOf(R.id.screenHome, R.id.screenFirmware, R.id.screenRestore, R.id.screenDiagnostics, R.id.screenTools)
        private val NAV_BUTTON_IDS = intArrayOf(R.id.navHomeButton, R.id.navDevicesButton, R.id.navFirmwareButton, R.id.navRestoreButton, R.id.navMoreButton)
    }
}
