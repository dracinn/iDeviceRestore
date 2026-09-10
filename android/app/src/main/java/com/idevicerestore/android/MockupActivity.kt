package com.idevicerestore.android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Clean presentation entry point for the mockup-first rebuild.
 *
 * This activity intentionally has no USB, firmware, restore, download, or diagnostic dependencies.
 * Those capabilities remain in the existing core and will only be connected after the five-screen
 * visual shell has been reviewed and approved.
 */
class MockupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mockup)
    }
}
