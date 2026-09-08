package com.idevicerestore.android

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
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

        binding.includeBetaFirmwareSwitch.isChecked = appSettings.includeBetaFirmware
        binding.includeBetaFirmwareSwitch.setOnCheckedChangeListener { _, checked ->
            appSettings.includeBetaFirmware = checked
        }
        binding.openBootDiagnosticsButton.setOnClickListener {
            startActivity(Intent(this, BootDiagnosticsActivity::class.java))
        }
        binding.doneButton.setOnClickListener { finish() }
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
