package com.bidyut.litevolume

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class FloatingControlService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        @Volatile
        var isRunning: Boolean = false
        private const val AUTO_COLLAPSE_DELAY_MS = 3000L
        private const val HOLD_INITIAL_DELAY_MS = 380L
        private const val HOLD_REPEAT_INTERVAL_MS = 150L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager

    private lateinit var btnHandle: FrameLayout
    private lateinit var ivHandleIcon: ImageView
    private lateinit var panelControls: LinearLayout
    private lateinit var btnVolUp: FrameLayout
    private lateinit var ivVolUp: ImageView
    private lateinit var btnVolDown: FrameLayout
    private lateinit var ivVolDown: ImageView
    private lateinit var btnPower: LinearLayout
    private lateinit var ivPower: ImageView
    private lateinit var tvPowerLabel: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val collapseRunnable = Runnable { collapsePanel() }
    private var repeatRunnable: Runnable? = null

    private var savedAnchorX = 0
    private var savedAnchorY = 500

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundNotification()

        prefs = getSharedPreferences("lite_vol_prefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)
        audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)

        btnHandle = floatingView.findViewById(R.id.btnHandle)
        ivHandleIcon = floatingView.findViewById(R.id.ivHandleIcon)
        panelControls = floatingView.findViewById(R.id.panelControls)
        btnVolUp = floatingView.findViewById(R.id.btnVolUp)
        ivVolUp = floatingView.findViewById(R.id.ivVolUp)
        btnVolDown = floatingView.findViewById(R.id.btnVolDown)
        ivVolDown = floatingView.findViewById(R.id.ivVolDown)
        btnPower = floatingView.findViewById(R.id.btnPower)
        ivPower = floatingView.findViewById(R.id.ivPower)
        tvPowerLabel = floatingView.findViewById(R.id.tvPowerLabel)

        val screenWidth = resources.displayMetrics.widthPixels
        val defaultX = (screenWidth - (64 * resources.displayMetrics.density).toInt()).coerceAtLeast(0)
        savedAnchorX = prefs.getInt("pos_x", defaultX)
        savedAnchorY = prefs.getInt("pos_y", 500)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedAnchorX
            y = savedAnchorY
        }

        setupTouchAndDrag()
        setupButtons()
        applySettings()

        windowManager.addView(floatingView, params)
    }

    private fun startForegroundNotification() {
        val channelId = "lite_vol_channel"
        val channel = NotificationChannel(
            channelId,
            "LiteVolume Floating Control",
            NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("LiteVolume Active")
            .setContentText("Floating volume & power button running")
            .setSmallIcon(R.drawable.ic_volume)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndDrag() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoved = false

        btnHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isMoved = true
                        val maxX = (resources.displayMetrics.widthPixels - floatingView.width).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - floatingView.height).coerceAtLeast(0)
                        params.x = (initialX + dx).coerceIn(0, maxX)
                        params.y = (initialY + dy).coerceIn(0, maxY)
                        windowManager.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isMoved) {
                        savedAnchorX = params.x
                        savedAnchorY = params.y
                        prefs.edit().putInt("pos_x", savedAnchorX).putInt("pos_y", savedAnchorY).apply()
                    } else {
                        expandPanel()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun expandPanel() {
        if (panelControls.visibility == View.VISIBLE) {
            resetCollapseTimer()
            return
        }

        // Save exact handle position before hiding it
        savedAnchorX = params.x
        savedAnchorY = params.y

        // Hide the floating circular button and show ONLY +, -, Power buttons
        btnHandle.visibility = View.GONE
        panelControls.visibility = View.VISIBLE

        panelControls.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val pad = (12 * resources.displayMetrics.density).toInt()
        val stackWidth = panelControls.measuredWidth + pad
        val stackHeight = panelControls.measuredHeight + pad
        val maxX = (resources.displayMetrics.widthPixels - stackWidth).coerceAtLeast(0)
        val maxY = (resources.displayMetrics.heightPixels - stackHeight - 24).coerceAtLeast(0)

        params.x = savedAnchorX.coerceIn(0, maxX)
        params.y = savedAnchorY.coerceIn(0, maxY)

        if (floatingView.isAttachedToWindow) {
            windowManager.updateViewLayout(floatingView, params)
        }

        triggerLiveSystemVolume(AudioManager.ADJUST_SAME)
        resetCollapseTimer()
    }

    private fun collapsePanel() {
        handler.removeCallbacks(collapseRunnable)
        stopVolumeRepeat()
        if (panelControls.visibility != View.VISIBLE && btnHandle.visibility == View.VISIBLE) return

        // Hide +, -, Power buttons and bring back the floating circular button
        panelControls.visibility = View.GONE
        btnHandle.visibility = View.VISIBLE

        val pad = (12 * resources.displayMetrics.density).toInt()
        val handleW = btnHandle.layoutParams.width + pad
        val handleH = btnHandle.layoutParams.height + pad
        val maxX = (resources.displayMetrics.widthPixels - handleW).coerceAtLeast(0)
        val maxY = (resources.displayMetrics.heightPixels - handleH).coerceAtLeast(0)

        params.x = savedAnchorX.coerceIn(0, maxX)
        params.y = savedAnchorY.coerceIn(0, maxY)

        if (floatingView.isAttachedToWindow) {
            windowManager.updateViewLayout(floatingView, params)
        }
    }

    private fun resetCollapseTimer() {
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, AUTO_COLLAPSE_DELAY_MS)
    }

    private fun triggerLiveSystemVolume(direction: Int) {
        val flags = AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_ALLOW_RINGER_MODES
        try {
            audioManager.adjustSuggestedStreamVolume(
                direction,
                AudioManager.STREAM_MUSIC,
                flags
            )
        } catch (_: Exception) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
        }
    }

    private fun stopVolumeRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindVolumeButton(buttonView: View, direction: Int) {
        buttonView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(70).start()
                    handler.removeCallbacks(collapseRunnable)
                    stopVolumeRepeat()

                    triggerLiveSystemVolume(direction)

                    val runnable = object : Runnable {
                        override fun run() {
                            triggerLiveSystemVolume(direction)
                            handler.postDelayed(this, HOLD_REPEAT_INTERVAL_MS)
                        }
                    }
                    repeatRunnable = runnable
                    handler.postDelayed(runnable, HOLD_INITIAL_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(70).start()
                    stopVolumeRepeat()
                    resetCollapseTimer()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupButtons() {
        bindVolumeButton(btnVolUp, AudioManager.ADJUST_RAISE)
        bindVolumeButton(btnVolDown, AudioManager.ADJUST_LOWER)

        btnPower.setOnClickListener {
            val service = PowerAccessibilityService.instance
            if (service != null) {
                collapsePanel()
                service.openPowerDialog()
            } else {
                Toast.makeText(
                    this,
                    "Enable Accessibility Permission in LiteVolume first",
                    Toast.LENGTH_SHORT
                ).show()
                resetCollapseTimer()
            }
        }
    }

    private fun applySettings() {
        val opacityPercent = prefs.getInt("opacity", 85).coerceIn(20, 100)
        floatingView.alpha = opacityPercent / 100f

        val showPower = prefs.getBoolean("show_power", true)
        btnPower.visibility = if (showPower) View.VISIBLE else View.GONE

        val sizePercent = prefs.getInt("size", 100).coerceIn(70, 150)
        val scale = sizePercent / 100f
        val density = resources.displayMetrics.density
        val buttonGapPx = (18 * density * scale).toInt()

        val handleSize = (48 * density * scale).toInt()
        val iconSize = (22 * density * scale).toInt()
        btnHandle.layoutParams = btnHandle.layoutParams.apply {
            width = handleSize
            height = handleSize
        }
        ivHandleIcon.layoutParams = ivHandleIcon.layoutParams.apply {
            width = iconSize
            height = iconSize
        }

        val actionBtnSize = (46 * density * scale).toInt()
        btnVolUp.layoutParams = btnVolUp.layoutParams.apply {
            width = actionBtnSize
            height = actionBtnSize
        }
        ivVolUp.layoutParams = ivVolUp.layoutParams.apply {
            width = iconSize
            height = iconSize
        }

        btnVolDown.layoutParams = (btnVolDown.layoutParams as LinearLayout.LayoutParams).apply {
            width = actionBtnSize
            height = actionBtnSize
            topMargin = buttonGapPx
        }
        ivVolDown.layoutParams = ivVolDown.layoutParams.apply {
            width = iconSize
            height = iconSize
        }

        val powerBtnSize = (50 * density * scale).toInt()
        val powerIconSize = (18 * density * scale).toInt()
        btnPower.layoutParams = (btnPower.layoutParams as LinearLayout.LayoutParams).apply {
            width = powerBtnSize
            height = powerBtnSize
            topMargin = buttonGapPx
        }
        ivPower.layoutParams = ivPower.layoutParams.apply {
            width = powerIconSize
            height = powerIconSize
        }
        tvPowerLabel.textSize = 8f * scale

        if (floatingView.isAttachedToWindow) {
            windowManager.updateViewLayout(floatingView, params)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        applySettings()
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(collapseRunnable)
        stopVolumeRepeat()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        if (::floatingView.isInitialized && floatingView.isAttachedToWindow) {
            windowManager.removeView(floatingView)
        }
        super.onDestroy()
    }
}
