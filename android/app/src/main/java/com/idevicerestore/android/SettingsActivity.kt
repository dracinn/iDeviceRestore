package com.idevicerestore.android

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.idevicerestore.android.databinding.ActivitySettingsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val appSettings by lazy { AppSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.automaticDeviceDetectionSwitch.isChecked = appSettings.automaticDeviceDetection
        binding.automaticDeviceDetectionSwitch.setOnCheckedChangeListener { _, checked ->
            appSettings.automaticDeviceDetection = checked
            UsbAutoDetectionPolicy.apply(this, checked)
        }

        binding.checkForUpdatesSwitch.isChecked = appSettings.checkForAppUpdatesAtLaunch
        binding.checkForUpdatesSwitch.setOnCheckedChangeListener { _, checked ->
            appSettings.checkForAppUpdatesAtLaunch = checked
        }

        binding.verboseLoggingSwitch.isChecked = appSettings.verboseLogging
        binding.verboseLoggingSwitch.setOnCheckedChangeListener { _, checked ->
            appSettings.verboseLogging = checked
        }

        binding.includeBetaFirmwareSwitch.isChecked = appSettings.includeBetaFirmware
        binding.includeBetaFirmwareSwitch.setOnCheckedChangeListener { _, checked ->
            appSettings.includeBetaFirmware = checked
        }

        binding.organizeFirmwareByDeviceSwitch.isChecked = appSettings.organizeFirmwareByDevice
        binding.organizeFirmwareByDeviceSwitch.setOnCheckedChangeListener { _, checked ->
            appSettings.organizeFirmwareByDevice = checked
        }

        when (appSettings.appearanceMode) {
            AppSettings.AppearanceMode.SYSTEM -> binding.appearanceSystemRadio.isChecked = true
            AppSettings.AppearanceMode.LIGHT -> binding.appearanceLightRadio.isChecked = true
            AppSettings.AppearanceMode.DARK -> binding.appearanceDarkRadio.isChecked = true
        }
        updateAppearanceSelectionStyle()
        binding.appearanceRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                binding.appearanceLightRadio.id -> AppSettings.AppearanceMode.LIGHT
                binding.appearanceDarkRadio.id -> AppSettings.AppearanceMode.DARK
                else -> AppSettings.AppearanceMode.SYSTEM
            }
            updateAppearanceSelectionStyle()
            if (selected != appSettings.appearanceMode) {
                appSettings.appearanceMode = selected
                AppCompatDelegate.setDefaultNightMode(selected.appCompatNightMode)
            }
        }

        binding.shareLogsButton.setOnClickListener { shareSettingsReport() }
        binding.openBootDiagnosticsButton.setOnClickListener {
            startActivity(Intent(this, BootDiagnosticsActivity::class.java))
        }
        binding.doneButton.setOnClickListener { finish() }
    }

    private fun shareSettingsReport() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val report = buildString {
            appendLine("iDeviceRestore diagnostic summary")
            appendLine("Generated: $timestamp")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
            appendLine("Host device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Settings schema: ${appSettings.schemaVersion}")
            appendLine("Automatic USB attach detection: ${appSettings.automaticDeviceDetection}")
            appendLine("Check for app updates at launch: ${appSettings.checkForAppUpdatesAtLaunch}")
            appendLine("Verbose diagnostic logging: ${appSettings.verboseLogging}")
            appendLine("Include beta/RC firmware: ${appSettings.includeBetaFirmware}")
            appendLine("Organize firmware by device: ${appSettings.organizeFirmwareByDevice}")
            appendLine("Firmware downloader: official aria2c, up to 8 connections")
            appendLine("Appearance: ${appSettings.appearanceMode.storedValue}")
            appendLine("Privacy: ECID and Apple serial number are redacted from shared reports")
            appendLine("---")
            appendLine(RestorePreflightEvidenceStore.preflightSummary())
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "iDeviceRestore diagnostic log")
            putExtra(Intent.EXTRA_TEXT, AppLogger.redact(report))
        }
        startActivity(Intent.createChooser(share, "Share logs"))
    }

    private fun updateAppearanceSelectionStyle() {
        styleAppearanceButton(binding.appearanceSystemRadio)
        styleAppearanceButton(binding.appearanceLightRadio)
        styleAppearanceButton(binding.appearanceDarkRadio)
    }

    private fun styleAppearanceButton(button: RadioButton) {
        val selected = button.isChecked
        val density = resources.displayMetrics.density
        val fill = ContextCompat.getColor(
            this,
            if (selected) R.color.mock_primary else R.color.mock_surface_secondary
        )
        val stroke = ContextCompat.getColor(
            this,
            if (selected) R.color.mock_primary else R.color.mock_separator
        )
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(fill)
            setStroke((1f * density).toInt().coerceAtLeast(1), stroke)
        }
        button.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) android.R.color.white else R.color.mock_text_primary
            )
        )
    }
}
