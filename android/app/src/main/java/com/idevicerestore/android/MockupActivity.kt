package com.idevicerestore.android

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Presentation-only shell for matching the approved mockup before backend integration. */
class MockupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); showHome() }

    private fun showHome() {
        setContentView(R.layout.activity_mockup)
        findViewById<View>(R.id.mockFirmware).setOnClickListener { showFirmware() }
        findViewById<View>(R.id.mockSettings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.mockRestore).setOnClickListener { showRestore() }
        findViewById<View>(R.id.mockDiagnostics).setOnClickListener { showDiagnostics() }
    }

    private fun showFirmware() {
        setContentView(R.layout.screen_firmware_mockup)
        bindSharedNav(active = NavItem.FIRMWARE)
    }

    private fun showRestore() {
        setContentView(R.layout.screen_restore_mockup)
        bindSharedNav(active = null)
        findViewById<View>(R.id.restoreBack).setOnClickListener { showHome() }
    }

    private fun showDiagnostics() {
        setContentView(R.layout.screen_diagnostics_mockup)
        bindSharedNav(active = NavItem.TOOLS)
        findViewById<View>(R.id.diagnosticsBack).setOnClickListener { showHome() }
    }

    private fun showSettings() {
        setContentView(R.layout.screen_settings_mockup)
        bindSharedNav(active = NavItem.TOOLS)
        findViewById<View>(R.id.settingsBack).setOnClickListener { showHome() }
    }

    private fun bindSharedNav(active: NavItem?) {
        findViewById<View>(R.id.navHome).setOnClickListener { showHome() }
        findViewById<View>(R.id.navFirmware).setOnClickListener { showFirmware() }
        findViewById<View>(R.id.navTools).setOnClickListener { showDiagnostics() }

        val primary = ContextCompat.getColor(this, R.color.ui_primary)
        val secondary = ContextCompat.getColor(this, R.color.ui_text_secondary)
        listOf(
            NavItem.HOME to Pair(R.id.navHomeIcon, R.id.navHomeLabel),
            NavItem.FIRMWARE to Pair(R.id.navFirmwareIcon, R.id.navFirmwareLabel),
            NavItem.DEVICES to Pair(R.id.navDevicesIcon, R.id.navDevicesLabel),
            NavItem.TOOLS to Pair(R.id.navToolsIcon, R.id.navToolsLabel)
        ).forEach { (item, ids) ->
            val color = if (item == active) primary else secondary
            findViewById<ImageView>(ids.first).imageTintList = ColorStateList.valueOf(color)
            findViewById<TextView>(ids.second).setTextColor(color)
        }
    }

    private enum class NavItem { HOME, FIRMWARE, DEVICES, TOOLS }
}
