package com.idevicerestore.android

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.idevicerestore.android.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val appSettings by lazy { AppSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        when (appSettings.appearanceMode) {
            AppSettings.AppearanceMode.SYSTEM -> binding.appearanceSystemRadio.isChecked = true
            AppSettings.AppearanceMode.LIGHT -> binding.appearanceLightRadio.isChecked = true
            AppSettings.AppearanceMode.DARK -> binding.appearanceDarkRadio.isChecked = true
        }
        binding.appearanceRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                binding.appearanceLightRadio.id -> AppSettings.AppearanceMode.LIGHT
                binding.appearanceDarkRadio.id -> AppSettings.AppearanceMode.DARK
                else -> AppSettings.AppearanceMode.SYSTEM
            }
            if (selected != appSettings.appearanceMode) {
                appSettings.appearanceMode = selected
                AppCompatDelegate.setDefaultNightMode(selected.appCompatNightMode)
            }
        }

        binding.includeBetaFirmwareSwitch.isChecked = appSettings.includeBetaFirmware
        binding.includeBetaFirmwareSwitch.setOnCheckedChangeListener { _, checked ->
            appSettings.includeBetaFirmware = checked
        }
        binding.openBootDiagnosticsButton.setOnClickListener {
            startActivity(Intent(this, BootDiagnosticsActivity::class.java))
        }
        binding.doneButton.setOnClickListener { finish() }
    }
}
