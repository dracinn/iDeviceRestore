package com.idevicerestore.android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.idevicerestore.android.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val appSettings by lazy { AppSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.includeBetaFirmwareSwitch.isChecked = appSettings.includeBetaFirmware
        binding.includeBetaFirmwareSwitch.setOnCheckedChangeListener { _, checked ->
            appSettings.includeBetaFirmware = checked
        }
        binding.doneButton.setOnClickListener { finish() }
    }
}
