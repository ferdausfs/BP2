package com.haramblur.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import com.haramblur.databinding.ActivityMainBinding
import com.haramblur.service.HaramBlurForegroundService
import com.haramblur.utils.Settings as AppSettings

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var s: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        s = AppSettings(this)
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
    }

    private fun setupUI() {
        // Master toggle
        b.switchEnable.isChecked = s.isEnabled
        b.switchEnable.setOnCheckedChangeListener { _, on ->
            s.isEnabled = on
            if (on) startForegroundService(Intent(this, HaramBlurForegroundService::class.java))
            else    stopService(Intent(this, HaramBlurForegroundService::class.java))
        }

        // Strictness  0-100 → 0.0-1.0
        b.sliderStrictness.value = s.strictness * 100f
        b.sliderStrictness.addOnChangeListener { _, v, _ -> s.strictness = v / 100f }

        // Blur amount
        b.sliderBlurAmount.value = s.blurAmount.toFloat()
        b.sliderBlurAmount.addOnChangeListener { _, v, _ -> s.blurAmount = v.toInt() }

        // Blur female / male
        b.switchBlurFemale.isChecked = s.blurFemale
        b.switchBlurFemale.setOnCheckedChangeListener { _, on -> s.blurFemale = on }

        b.switchBlurMale.isChecked = s.blurMale
        b.switchBlurMale.setOnCheckedChangeListener { _, on -> s.blurMale = on }

        // Accessibility button
        b.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Whitelist button
        b.btnWhitelist.setOnClickListener {
            startActivity(Intent(this, WhitelistActivity::class.java))
        }
    }

    private fun refreshAccessibilityStatus() {
        val enabled = isAccessibilityEnabled()
        b.tvAccessibilityStatus.text = if (enabled)
            "✅ Accessibility: Enabled"
        else
            "❌ Accessibility: Disabled — tap button below to enable"
        b.btnAccessibility.isEnabled = !enabled
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName, ignoreCase = true)
    }
}
