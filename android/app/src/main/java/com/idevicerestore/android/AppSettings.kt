package com.idevicerestore.android

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("idevicerestore_settings", Context.MODE_PRIVATE)

    init {
        migrateIfNeeded()
    }

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

    var aria2Connections: Int
        get() = preferences.getInt(KEY_ARIA2_CONNECTIONS, DEFAULT_ARIA2_CONNECTIONS).coerceIn(1, 16)
        set(value) = preferences.edit().putInt(KEY_ARIA2_CONNECTIONS, value.coerceIn(1, 16)).apply()

    var projectFolderName: String
        get() = sanitizeProjectFolderName(
            preferences.getString(KEY_PROJECT_FOLDER_NAME, DEFAULT_PROJECT_FOLDER_NAME)
                ?: DEFAULT_PROJECT_FOLDER_NAME
        )
        set(value) = preferences.edit()
            .putString(KEY_PROJECT_FOLDER_NAME, sanitizeProjectFolderName(value))
            .apply()

    var appearanceMode: AppearanceMode
        get() = AppearanceMode.fromStoredValue(preferences.getString(KEY_APPEARANCE_MODE, null))
        set(value) = preferences.edit().putString(KEY_APPEARANCE_MODE, value.storedValue).apply()

    val schemaVersion: Int
        get() = preferences.getInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)

    private fun migrateIfNeeded() {
        val current = preferences.getInt(KEY_SCHEMA_VERSION, 0)
        if (current >= CURRENT_SCHEMA_VERSION) return

        val editor = preferences.edit()
        if (current < 1) {
            // Preserve the behavior users had before these switches became configurable.
            if (!preferences.contains(KEY_AUTOMATIC_DEVICE_DETECTION)) {
                editor.putBoolean(KEY_AUTOMATIC_DEVICE_DETECTION, true)
            }
            if (!preferences.contains(KEY_CHECK_FOR_APP_UPDATES)) {
                editor.putBoolean(KEY_CHECK_FOR_APP_UPDATES, true)
            }
            if (!preferences.contains(KEY_VERBOSE_LOGGING)) {
                editor.putBoolean(KEY_VERBOSE_LOGGING, false)
            }
            if (!preferences.contains(KEY_ORGANIZE_FIRMWARE_BY_DEVICE)) {
                editor.putBoolean(KEY_ORGANIZE_FIRMWARE_BY_DEVICE, true)
            }
        }
        if (current < 2 && !preferences.contains(KEY_ARIA2_CONNECTIONS)) {
            // Existing production builds used eight aria2 connections.
            editor.putInt(KEY_ARIA2_CONNECTIONS, DEFAULT_ARIA2_CONNECTIONS)
        }
        if (current < 3 && !preferences.contains(KEY_PROJECT_FOLDER_NAME)) {
            // Existing installs always used /storage/emulated/0/iDeviceRestore.
            editor.putString(KEY_PROJECT_FOLDER_NAME, DEFAULT_PROJECT_FOLDER_NAME)
        }
        editor.putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION).apply()
    }

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
        const val DEFAULT_ARIA2_CONNECTIONS = 8
        const val DEFAULT_PROJECT_FOLDER_NAME = "iDeviceRestore"
        private const val CURRENT_SCHEMA_VERSION = 3
        private const val KEY_SCHEMA_VERSION = "settings_schema_version"
        private const val KEY_AUTOMATIC_DEVICE_DETECTION = "automatic_device_detection"
        private const val KEY_CHECK_FOR_APP_UPDATES = "check_for_app_updates_at_launch"
        private const val KEY_VERBOSE_LOGGING = "verbose_logging"
        private const val KEY_INCLUDE_BETA_FIRMWARE = "include_beta_firmware"
        private const val KEY_ORGANIZE_FIRMWARE_BY_DEVICE = "organize_firmware_by_device"
        private const val KEY_ARIA2_CONNECTIONS = "aria2_connections"
        private const val KEY_PROJECT_FOLDER_NAME = "project_folder_name"
        private const val KEY_APPEARANCE_MODE = "appearance_mode"

        fun sanitizeProjectFolderName(value: String): String {
            val cleaned = value.trim()
                .replace(Regex("[^A-Za-z0-9._ -]+"), "_")
                .trim(' ', '.', '_')
                .take(80)
            return cleaned.ifBlank { DEFAULT_PROJECT_FOLDER_NAME }
        }
    }
}
