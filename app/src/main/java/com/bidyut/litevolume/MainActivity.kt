package com.bidyut.litevolume

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvServiceStatus: TextView
    private lateinit var switchService: SwitchMaterial
    private lateinit var badgeOverlayPerm: TextView
    private lateinit var badgeAccessPerm: TextView
    private lateinit var switchPower: SwitchMaterial
    private lateinit var tvPowerSub: TextView
    private lateinit var previewCircleHandle: FrameLayout
    private lateinit var previewPowerMini: TextView

    private var isUpdatingSwitchProgrammatically = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("lite_vol_prefs", Context.MODE_PRIVATE)

        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        switchService = findViewById(R.id.switchService)
        badgeOverlayPerm = findViewById(R.id.badgeOverlayPerm)
        badgeAccessPerm = findViewById(R.id.badgeAccessPerm)
        switchPower = findViewById(R.id.switchPower)
        tvPowerSub = findViewById(R.id.tvPowerSub)
        previewCircleHandle = findViewById(R.id.previewCircleHandle)
        previewPowerMini = findViewById(R.id.previewPowerMini)

        val rowOverlayPerm: LinearLayout = findViewById(R.id.rowOverlayPerm)
        val rowAccessPerm: LinearLayout = findViewById(R.id.rowAccessPerm)
        val tvOpacityValue: TextView = findViewById(R.id.tvOpacityValue)
        val seekOpacity: SeekBar = findViewById(R.id.seekOpacity)
        val tvSizeValue: TextView = findViewById(R.id.tvSizeValue)
        val seekSize: SeekBar = findViewById(R.id.seekSize)

        requestNotificationPermissionIfNeeded()

        // 1. Overlay Permission
        rowOverlayPerm.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        // 2. Accessibility Permission
        rowAccessPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 3. Service ON/OFF Toggle
        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener

            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    setServiceSwitchState(false)
                    Toast.makeText(this, "Grant Overlay Permission first", Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                val serviceIntent = Intent(this, FloatingControlService::class.java)
                startForegroundService(serviceIntent)
                tvServiceStatus.text = "Service Active"
            } else {
                stopService(Intent(this, FloatingControlService::class.java))
                tvServiceStatus.text = "Service Off"
            }
        }

        // 4. Power Button ON/OFF
        val showPowerInitial = prefs.getBoolean("show_power", true)
        switchPower.isChecked = showPowerInitial
        updatePowerStateUI(showPowerInitial)

        switchPower.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_power", isChecked).apply()
            updatePowerStateUI(isChecked)
        }

        // 5. Opacity Slider (20% - 100%)
        val savedOpacity = prefs.getInt("opacity", 85).coerceIn(20, 100)
        seekOpacity.progress = savedOpacity - 20
        tvOpacityValue.text = "$savedOpacity%"
        previewCircleHandle.alpha = savedOpacity / 100f

        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 20
                tvOpacityValue.text = "$value%"
                previewCircleHandle.alpha = value / 100f
                prefs.edit().putInt("opacity", value).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 6. Size Slider (70% - 150%)
        val savedSize = prefs.getInt("size", 100).coerceIn(70, 150)
        seekSize.progress = savedSize - 70
        tvSizeValue.text = "$savedSize%"
        updatePreviewSize(savedSize)

        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 70
                tvSizeValue.text = "$value%"
                updatePreviewSize(value)
                prefs.edit().putInt("size", value).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun updatePowerStateUI(enabled: Boolean) {
        tvPowerSub.text = if (enabled) "Enabled" else "Disabled"
        previewPowerMini.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun updatePreviewSize(sizePercent: Int) {
        val scale = sizePercent / 100f
        val px = (48 * resources.displayMetrics.density * scale).toInt()
        previewCircleHandle.layoutParams = previewCircleHandle.layoutParams.apply {
            width = px
            height = px
        }
    }

    private fun setServiceSwitchState(checked: Boolean) {
        isUpdatingSwitchProgrammatically = true
        switchService.isChecked = checked
        tvServiceStatus.text = if (checked) "Service Active" else "Service Off"
        isUpdatingSwitchProgrammatically = false
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    201
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()

        val overlayGranted = Settings.canDrawOverlays(this)
        if (overlayGranted) {
            badgeOverlayPerm.text = "Granted ✓"
            badgeOverlayPerm.setBackgroundResource(R.drawable.bg_badge_granted)
            badgeOverlayPerm.setTextColor(ContextCompat.getColor(this, R.color.badge_green_text))
        } else {
            badgeOverlayPerm.text = "Grant"
            badgeOverlayPerm.setBackgroundResource(R.drawable.bg_badge_grant)
            badgeOverlayPerm.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }

        val accessGranted = PowerAccessibilityService.instance != null
        if (accessGranted) {
            badgeAccessPerm.text = "Granted ✓"
            badgeAccessPerm.setBackgroundResource(R.drawable.bg_badge_granted)
            badgeAccessPerm.setTextColor(ContextCompat.getColor(this, R.color.badge_green_text))
        } else {
            badgeAccessPerm.text = "Grant"
            badgeAccessPerm.setBackgroundResource(R.drawable.bg_badge_grant)
            badgeAccessPerm.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }

        setServiceSwitchState(FloatingControlService.isRunning)
    }
}
