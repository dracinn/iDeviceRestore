package com.idevicerestore.android

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("idevicerestore_settings", Context.MODE_PRIVATE)

    var automaticDeviceDetection: Boolean
        get() = preferences.getBoolean(KEY_AUTOMATIC_DEVICE_DETECTION, true)
        set(value) = preferences.edit().putBoolean(KEY_AUTOMATIC_DEVICE_DETECTION, value).apply()

    var checkForAppUpdatesAtLaunch: Boolean
        get() = preferences.getBoolean(KEY_CHECK_FOR_APP_UPDATES, true)
        set(value) = preferences.edit().putBoolean(KEY_CHECK_FOR_APP_UPDATES, value).apply()

    var verboseLogging: Boolean
        get() = preferences.getBoolean(KEY_VERBOSE_LOGGING, false)
        set(value) = preferences.edit().putBoolean(KEY_VERBOSE_LOGGING, value).apply()

    var includeBetaFirmware: Boolean
        get() = preferences.getBoolean(KEY_INCLUDE_BETA_FIRMWARE, false)
        set(value) = preferences.edit().putBoolean(KEY_INCLUDE_BETA_FIRMWARE, value).apply()

    var organizeFirmwareByDevice: Boolean
        get() = preferences.getBoolean(KEY_ORGANIZE_FIRMWARE_BY_DEVICE, true)
        set(value) = preferences.edit().putBoolean(KEY_ORGANIZE_FIRMWARE_BY_DEVICE, value).apply()

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
        private const val KEY_AUTOMATIC_DEVICE_DETECTION = "automatic_device_detection"
        private const val KEY_CHECK_FOR_APP_UPDATES = "check_for_app_updates_at_launch"
        private const val KEY_VERBOSE_LOGGING = "verbose_logging"
        private const val KEY_INCLUDE_BETA_FIRMWARE = "include_beta_firmware"
        private const val KEY_ORGANIZE_FIRMWARE_BY_DEVICE = "organize_firmware_by_device"
        private const val KEY_APPEARANCE_MODE = "appearance_mode"
    }
}
