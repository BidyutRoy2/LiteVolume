package com.bidyut.litevolume

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val VERSION_JSON_URL =
            "https://raw.githubusercontent.com/BidyutRoy2/LiteVolume/main/version.json"
        private const val DEFAULT_APK_URL =
            "https://raw.githubusercontent.com/BidyutRoy2/LiteVolume/main/LiteVolume.apk"
        private const val GITHUB_PROFILE_URL =
            "https://github.com/BidyutRoy2"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tvServiceStatus: TextView
    private lateinit var switchService: SwitchMaterial
    private lateinit var badgeOverlayPerm: TextView
    private lateinit var badgeAccessPerm: TextView
    private lateinit var switchPower: SwitchMaterial
    private lateinit var tvPowerSub: TextView
    private lateinit var previewCircleHandle: FrameLayout
    private lateinit var previewPowerMini: TextView

    // In-App GitHub Updater UI
    private lateinit var tvCurrentVersion: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var btnCheckUpdate: TextView
    private lateinit var progressUpdate: ProgressBar
    private lateinit var btnInstallUpdate: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var latestApkUrl: String = DEFAULT_APK_URL
    private var isDownloading = false
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

        tvCurrentVersion = findViewById(R.id.tvCurrentVersion)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        progressUpdate = findViewById(R.id.progressUpdate)
        btnInstallUpdate = findViewById(R.id.btnInstallUpdate)

        val rowOverlayPerm: LinearLayout = findViewById(R.id.rowOverlayPerm)
        val rowAccessPerm: LinearLayout = findViewById(R.id.rowAccessPerm)
        val tvOpacityValue: TextView = findViewById(R.id.tvOpacityValue)
        val seekOpacity: SeekBar = findViewById(R.id.seekOpacity)
        val tvSizeValue: TextView = findViewById(R.id.tvSizeValue)
        val seekSize: SeekBar = findViewById(R.id.seekSize)
        val cardDeveloperFooter: LinearLayout = findViewById(R.id.cardDeveloperFooter)
        val btnGithubProfile: LinearLayout = findViewById(R.id.btnGithubProfile)

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

        // 7. In-App GitHub Updater Setup
        setupUpdaterUI()

        // 8. Developer Footer GitHub Profile Link
        val openGithub = View.OnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_PROFILE_URL)))
            } catch (_: Exception) {
                Toast.makeText(this, GITHUB_PROFILE_URL, Toast.LENGTH_SHORT).show()
            }
        }
        cardDeveloperFooter.setOnClickListener(openGithub)
        btnGithubProfile.setOnClickListener(openGithub)
    }

    private fun getInstalledVersionInfo(): Pair<Long, String> {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val code = PackageInfoCompat.getLongVersionCode(pInfo)
            val name = pInfo.versionName ?: "1.0"
            Pair(code, name)
        } catch (_: Exception) {
            Pair(1L, "1.0")
        }
    }

    private fun setupUpdaterUI() {
        val (_, currentName) = getInstalledVersionInfo()
        tvCurrentVersion.text = "Installed: v$currentName"

        btnCheckUpdate.setOnClickListener {
            if (!isDownloading) {
                checkForUpdates(isManual = true)
            }
        }

        btnInstallUpdate.setOnClickListener {
            if (!isDownloading) {
                startApkDownloadAndInstall()
            }
        }

        // Auto-check silently when settings screen opens
        checkForUpdates(isManual = false)
    }

    private fun checkForUpdates(isManual: Boolean) {
        val (localCode, localName) = getInstalledVersionInfo()
        tvUpdateStatus.text = "Checking GitHub for updates..."
        btnCheckUpdate.isEnabled = false

        executor.execute {
            try {
                val url = URL("$VERSION_JSON_URL?t=${System.currentTimeMillis()}")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    useCaches = false
                }
                val code = conn.responseCode
                if (code != 200) {
                    throw Exception("HTTP $code")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = JSONObject(body)
                val remoteCode = json.getLong("versionCode")
                val remoteName = json.optString("versionName", remoteCode.toString())
                val apkUrl = json.optString("apkUrl", DEFAULT_APK_URL)

                mainHandler.post {
                    btnCheckUpdate.isEnabled = true
                    latestApkUrl = apkUrl
                    if (remoteCode > localCode) {
                        tvUpdateStatus.text = "New version available: v$remoteName (current v$localName)"
                        tvUpdateStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_green_text))
                        btnInstallUpdate.text = "Download & Upgrade to v$remoteName"
                        btnInstallUpdate.visibility = View.VISIBLE
                    } else {
                        tvUpdateStatus.text = "You are on the latest version (v$localName)"
                        tvUpdateStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                        btnInstallUpdate.visibility = View.GONE
                        if (isManual) {
                            Toast.makeText(this, "Already up to date!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    btnCheckUpdate.isEnabled = true
                    tvUpdateStatus.text = if (isManual) {
                        "Could not check update (${e.message ?: "No internet"})"
                    } else {
                        "Tap Check to look for latest GitHub build"
                    }
                    tvUpdateStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                }
            }
        }
    }

    private fun startApkDownloadAndInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this,
                "Allow 'Install unknown apps' for LiteVolume, then tap Upgrade again",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        isDownloading = true
        btnCheckUpdate.isEnabled = false
        btnInstallUpdate.isEnabled = false
        btnInstallUpdate.text = "Downloading APK..."
        progressUpdate.visibility = View.VISIBLE
        progressUpdate.isIndeterminate = false
        progressUpdate.progress = 0

        executor.execute {
            try {
                val updateDir = File(cacheDir, "updates").apply { mkdirs() }
                val outFile = File(updateDir, "LiteVolume-update.apk")
                if (outFile.exists()) outFile.delete()

                val url = URL("$latestApkUrl?t=${System.currentTimeMillis()}")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    useCaches = false
                }
                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    throw Exception("Download failed (HTTP $responseCode)")
                }

                val totalBytes = conn.contentLengthLong
                var downloadedBytes = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val pct = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                mainHandler.post {
                                    progressUpdate.progress = pct
                                    tvUpdateStatus.text = "Downloading update... $pct%"
                                }
                            }
                        }
                    }
                }
                conn.disconnect()

                mainHandler.post {
                    isDownloading = false
                    btnCheckUpdate.isEnabled = true
                    btnInstallUpdate.isEnabled = true
                    btnInstallUpdate.text = "Install Downloaded Update"
                    progressUpdate.visibility = View.GONE
                    tvUpdateStatus.text = "Download complete. Launching installer..."
                    installDownloadedApk(outFile)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    isDownloading = false
                    btnCheckUpdate.isEnabled = true
                    btnInstallUpdate.isEnabled = true
                    btnInstallUpdate.text = "Retry Download & Upgrade"
                    progressUpdate.visibility = View.GONE
                    tvUpdateStatus.text = "Update error: ${e.message ?: "Failed"}"
                }
            }
        }
    }

    private fun installDownloadedApk(apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Installer error: ${e.message}", Toast.LENGTH_LONG).show()
        }
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

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
