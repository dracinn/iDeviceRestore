package com.idevicerestore.android

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("idevicerestore_settings", Context.MODE_PRIVATE)

    var includeBetaFirmware: Boolean
        get() = preferences.getBoolean(KEY_INCLUDE_BETA_FIRMWARE, false)
        set(value) = preferences.edit().putBoolean(KEY_INCLUDE_BETA_FIRMWARE, value).apply()

    var appearanceMode: AppearanceMode
        get() = AppearanceMode.fromStoredValue(preferences.getString(KEY_APPEARANCE_MODE, null))
        set(value) = preferences.edit().putString(KEY_APPEARANCE_MODE, value.storedValue).apply()

    enum class AppearanceMode(val storedValue: String, val appCompatNightMode: Int) {
        SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

        companion object {
            fun fromStoredValue(value: String?): AppearanceMode =
                entries.firstOrNull { it.storedValue == value } ?: SYSTEM
        }
    }

    companion object {
        private const val KEY_INCLUDE_BETA_FIRMWARE = "include_beta_firmware"
        private const val KEY_APPEARANCE_MODE = "appearance_mode"
    }
}
