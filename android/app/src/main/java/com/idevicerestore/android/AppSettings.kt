package com.idevicerestore.android

import android.content.Context

class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("idevicerestore_settings", Context.MODE_PRIVATE)

    var includeBetaFirmware: Boolean
        get() = preferences.getBoolean(KEY_INCLUDE_BETA_FIRMWARE, false)
        set(value) = preferences.edit().putBoolean(KEY_INCLUDE_BETA_FIRMWARE, value).apply()

    companion object {
        private const val KEY_INCLUDE_BETA_FIRMWARE = "include_beta_firmware"
    }
}
