package com.idevicerestore.android

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private val firmwareStorage by lazy { FirmwareStorage(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        refreshStoragePath()
        binding.appVersionText.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        updateAria2ConnectionValue()

        // The reference layout predates a stable row id. Resolve the containing row from the
        // bound path label so existing installs/layouts gain a functional directory control without
        // introducing another visual-only setting.
        val downloadDirectoryRow = binding.downloadDirectoryText.parent?.parent as? View
        downloadDirectoryRow?.apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { showDownloadDirectoryChooser() }
        }
        binding.downloadDirectoryText.setOnClickListener { showDownloadDirectoryChooser() }

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

        binding.aria2ConnectionsRow.setOnClickListener { showAria2ConnectionsChooser() }

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

    private fun showDownloadDirectoryChooser() {
        if (!firmwareStorage.hasSharedStorageAccess()) {
            AlertDialog.Builder(this)
                .setTitle("Storage access required")
                .setMessage("Grant All files access from the main app before changing the firmware download directory.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (FirmwareDownloadService.isDownloadActive()) {
            AlertDialog.Builder(this)
                .setTitle("Firmware download active")
                .setMessage("Finish or cancel the active firmware download before moving the download directory.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val input = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(appSettings.projectFolderName)
            setSelection(text.length)
            hint = AppSettings.DEFAULT_PROJECT_FOLDER_NAME
            setPadding(48, 12, 48, 12)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Default Download Directory")
            .setMessage("Choose the top-level folder name on primary shared storage. Existing iDeviceRestore data will be moved by rename when possible.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Move", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val requested = input.text?.toString().orEmpty()
                val sanitized = AppSettings.sanitizeProjectFolderName(requested)
                val result = runCatching { firmwareStorage.migrateProjectRootFolder(sanitized) }
                    .getOrElse { error ->
                        AlertDialog.Builder(this)
                            .setTitle("Could not move folder")
                            .setMessage(error.message ?: error.javaClass.simpleName)
                            .setPositiveButton("OK", null)
                            .show()
                        return@setOnClickListener
                    }

                if (result.success) {
                    refreshStoragePath()
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Could not move folder")
                        .setMessage(result.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
        dialog.show()
    }

    private fun refreshStoragePath() {
        binding.downloadDirectoryText.text = firmwareStorage.projectRoot.absolutePath
    }

    private fun showAria2ConnectionsChooser() {
        val choices = intArrayOf(1, 2, 4, 8, 16)
        val labels = choices.map { "$it connection${if (it == 1) "" else "s"}" }.toTypedArray()
        val selectedIndex = choices.indexOf(appSettings.aria2Connections).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("aria2c Connections")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                appSettings.aria2Connections = choices[which]
                updateAria2ConnectionValue()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateAria2ConnectionValue() {
        binding.aria2ConnectionsValue.text = "${appSettings.aria2Connections}   ›"
    }

    private fun shareSettingsReport() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val session = SessionLogSnapshotStore.snapshot()
        val report = buildString {
            appendLine("iDeviceRestore diagnostic log")
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
            appendLine("Firmware project root: ${firmwareStorage.projectRoot.absolutePath}")
            appendLine("Firmware downloader: official aria2c, ${appSettings.aria2Connections} connection(s)")
            appendLine("Appearance: ${appSettings.appearanceMode.storedValue}")
            appendLine("Privacy: ECID and Apple serial number are redacted from shared reports")
            appendLine("---")
            appendLine(RestorePreflightEvidenceStore.preflightSummary())
            appendLine("---")
            appendLine("=== Activity Log ===")
            if (session.activityLog.isBlank()) {
                appendLine("No activity log entries captured in this process.")
            } else {
                append(session.activityLog)
                if (!session.activityLog.endsWith('\n')) appendLine()
            }
            appendLine("=== Probe Log ===")
            if (session.probeLog.isBlank()) {
                appendLine("No USB probe log entries captured in this process.")
            } else {
                append(session.probeLog)
            }
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
