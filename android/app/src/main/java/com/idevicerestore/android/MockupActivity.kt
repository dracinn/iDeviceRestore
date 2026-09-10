package com.idevicerestore.android

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

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
    private fun showFirmware() { setContentView(R.layout.screen_firmware_mockup); findViewById<View>(R.id.firmwareHome).setOnClickListener { showHome() } }
    private fun showRestore() { setContentView(R.layout.screen_restore_mockup); bindSharedNav(); findViewById<View>(R.id.restoreBack).setOnClickListener { showHome() } }
    private fun showDiagnostics() { setContentView(R.layout.screen_diagnostics_mockup); bindSharedNav(); findViewById<View>(R.id.diagnosticsBack).setOnClickListener { showHome() } }
    private fun showSettings() { setContentView(R.layout.screen_settings_mockup); bindSharedNav(); findViewById<View>(R.id.settingsBack).setOnClickListener { showHome() } }
    private fun bindSharedNav() {
        findViewById<View>(R.id.navHome).setOnClickListener { showHome() }
        findViewById<View>(R.id.navFirmware).setOnClickListener { showFirmware() }
        findViewById<View>(R.id.navTools).setOnClickListener { showDiagnostics() }
    }
}
