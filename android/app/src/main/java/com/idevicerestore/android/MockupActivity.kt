package com.idevicerestore.android

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/** Presentation-only shell for matching the approved mockup before backend integration. */
class MockupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun showHome() {
        setContentView(R.layout.activity_mockup)
        findViewById<View>(R.id.mockFirmware).setOnClickListener { showFirmware() }
        findViewById<View>(R.id.mockSettings).setOnClickListener { /* Settings screen follows in UI pass. */ }
        findViewById<View>(R.id.mockRestore).setOnClickListener { /* Restore screen follows in UI pass. */ }
        findViewById<View>(R.id.mockDiagnostics).setOnClickListener { /* Diagnostics screen follows in UI pass. */ }
    }

    private fun showFirmware() {
        setContentView(R.layout.screen_firmware_mockup)
        findViewById<View>(R.id.firmwareHome).setOnClickListener { showHome() }
    }
}
