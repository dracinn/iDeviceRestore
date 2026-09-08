package com.idevicerestore.android

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class IDeviceRestoreApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val mode = AppSettings(this).appearanceMode.appCompatNightMode
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
