package com.heisenberg.lux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.lifecycle.LifecycleOwner

class DetectionService : Service(), LifecycleOwner {
    private val serviceLifecycleProvider = ServiceLifecycleProvider()
    override val lifecycle
        get() = serviceLifecycleProvider.lifecycle
    private lateinit var prefs: SharedPreferences
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var powerManager: PowerManager
    private var cameraDetector: MotionDetector? = null
    private var lightDetector: LightDetector? = null
    private var isScreenOffReceiverRegistered = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            MainActivity.KEY_CAMERA_SELECTION,
            MainActivity.KEY_CAMERA_SENSITIVITY,
            MainActivity.KEY_LIGHT_SENSITIVITY -> {
                updateDetectors()
            }
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "Screen turned off, resuming detection")
                    resumeDetection()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "Screen turned on, pausing detection")
                    pauseDetection()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceLifecycleProvider.onStart()
        Log.i(TAG, "Service created")

        prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "HeisenbergLux::WakeLock"
        )

        // Register screen on/off receiver
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenOffReceiver, screenFilter)
        isScreenOffReceiverRegistered = true

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        updateDetectors()

        // Pause detection initially if screen is on
        if (powerManager.isInteractive) {
            Log.i(TAG, "Screen is on at service start, pausing detection")
            pauseDetection()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACTIVITY_FOREGROUND -> {
                Log.i(TAG, "MainActivity in foreground, resuming detection")
                resumeDetection()
            }
            ACTION_ACTIVITY_BACKGROUND -> {
                Log.i(TAG, "MainActivity in background")
                // Check if screen is on and pause if needed
                if (powerManager.isInteractive) {
                    pauseDetection()
                }
            }
        }
        Log.i(TAG, "Service started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceLifecycleProvider.onDestroy()
        Log.i(TAG, "Service destroyed")
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)

        if (isScreenOffReceiverRegistered) {
            try {
                unregisterReceiver(screenOffReceiver)
                isScreenOffReceiverRegistered = false
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Screen receiver not registered or already unregistered")
            }
        }

        cameraDetector?.stop()
        lightDetector?.stop()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateDetectors() {
        val cameraSelection = prefs.getInt(MainActivity.KEY_CAMERA_SELECTION, MainActivity.CAMERA_NONE)
        val cameraSensitivity = prefs.getInt(MainActivity.KEY_CAMERA_SENSITIVITY, 50)
        val lightSensitivity = prefs.getInt(MainActivity.KEY_LIGHT_SENSITIVITY, 0)

        // Update camera detection based on selection
        when (cameraSelection) {
            MainActivity.CAMERA_REAR, MainActivity.CAMERA_FRONT -> {
                val cameraSelector = if (cameraSelection == MainActivity.CAMERA_REAR) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }

                if (cameraDetector == null) {
                    cameraDetector = MotionDetector(
                        this,
                        cameraSensitivity,
                        cameraSelector,
                        onMotionDetected = { wakeScreen() },
                        onLevelUpdate = { level, threshold ->
                            broadcastSensorUpdate(SENSOR_CAMERA, level.toFloat(), threshold.toFloat())
                        }
                    )
                    cameraDetector?.start()
                } else {
                    cameraDetector?.updateSensitivity(cameraSensitivity)
                }
            }
            MainActivity.CAMERA_NONE -> {
                cameraDetector?.stop()
                cameraDetector = null
            }
        }

        // Update light detection
        if (lightSensitivity > 0) {
            if (lightDetector == null) {
                lightDetector = LightDetector(
                    this,
                    lightSensitivity,
                    onLightChangeDetected = { wakeScreen() },
                    onLevelUpdate = { level, threshold ->
                        broadcastSensorUpdate(SENSOR_LIGHT, level, threshold)
                    }
                )
                lightDetector?.start()
            } else {
                lightDetector?.updateSensitivity(lightSensitivity)
            }
        } else {
            lightDetector?.stop()
            lightDetector = null
        }

        // Stop service if all disabled
        if (cameraSelection == MainActivity.CAMERA_NONE && lightSensitivity == 0) {
            stopSelf()
        }
    }

    private fun pauseDetection() {
        cameraDetector?.pause()
        lightDetector?.pause()
        Log.d(TAG, "Detection paused")
    }

    private fun resumeDetection() {
        cameraDetector?.resume()
        lightDetector?.resume()
        Log.d(TAG, "Detection resumed")
    }

    private fun wakeScreen() {
        try {
            // CRITICAL FIX: Use acquire with timeout, don't release immediately
            // The timeout will auto-release after WAKE_DURATION_MS
            if (!wakeLock.isHeld) {
                Log.i(TAG, "Waking screen")
                wakeLock.acquire(WAKE_DURATION_MS)
                // Don't call release() - the timeout handles it automatically
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error waking screen", e)
        }
    }

    private fun broadcastSensorUpdate(sensorType: String, currentLevel: Float, threshold: Float) {
        val intent = Intent(ACTION_SENSOR_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_SENSOR_TYPE, sensorType)
            putExtra(EXTRA_CURRENT_LEVEL, currentLevel)
            putExtra(EXTRA_THRESHOLD, threshold)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    companion object {
        private const val TAG = "DetectionService"
        private const val CHANNEL_ID = "detection_service"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_DURATION_MS = 100L
        const val ACTION_SENSOR_UPDATE = "com.heisenberg.lux.SENSOR_UPDATE"
        const val ACTION_ACTIVITY_FOREGROUND = "com.heisenberg.lux.ACTIVITY_FOREGROUND"
        const val ACTION_ACTIVITY_BACKGROUND = "com.heisenberg.lux.ACTIVITY_BACKGROUND"
        const val EXTRA_SENSOR_TYPE = "sensor_type"
        const val EXTRA_CURRENT_LEVEL = "current_level"
        const val EXTRA_THRESHOLD = "threshold"
        const val SENSOR_CAMERA = "camera"
        const val SENSOR_LIGHT = "light"
    }
}
