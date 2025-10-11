package com.observer.effect

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
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
    private lateinit var keyguardManager: KeyguardManager
    private var cameraDetector: MotionDetector? = null
    private var currentCameraSelection: Int = MainActivity.CAMERA_NONE
    private var lightDetector: LightDetector? = null
    private var isScreenOffReceiverRegistered = false

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                MainActivity.KEY_CAMERA_SELECTION,
                MainActivity.KEY_CAMERA_SENSITIVITY,
                MainActivity.KEY_LIGHT_SENSITIVITY,
                -> {
                    updateDetectors()
                }
            }
        }

    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
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
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        // Note: These flags are deprecated in API 29+ but are the simplest way to wake the screen
        // from a background service without showing UI. Modern alternatives require launching an Activity.
        @Suppress("DEPRECATION")
        wakeLock =
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ObserverEffect::WakeLock",
            )

        // Register screen on/off receiver
        val screenFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        registerReceiver(screenOffReceiver, screenFilter)
        isScreenOffReceiverRegistered = true

        // Create notification channel and start foreground
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_description)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(getString(R.string.service_notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        startForeground(NOTIFICATION_ID, notification)

        updateDetectors()

        // Pause detection initially if screen is on
        if (powerManager.isInteractive) {
            Log.i(TAG, "Screen is on at service start, pausing detection")
            pauseDetection()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
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
        val cameraSensitivity = prefs.getInt(MainActivity.KEY_CAMERA_SENSITIVITY, 0)
        val lightSensitivity = prefs.getInt(MainActivity.KEY_LIGHT_SENSITIVITY, 0)

        // Update camera detection based on selection
        if (cameraSelection == MainActivity.CAMERA_NONE || cameraSensitivity == 0) {
            // Stop camera detector if disabled
            cameraDetector?.let {
                Log.d(TAG, "Stopping camera detector")
                it.stop()
                cameraDetector = null
            }
            currentCameraSelection = MainActivity.CAMERA_NONE
        } else {
            // Camera is enabled - create or update detector
            val cameraSelector =
                if (cameraSelection == MainActivity.CAMERA_REAR) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }

            if (cameraDetector == null || currentCameraSelection != cameraSelection) {
                // Create new detector (or recreate if camera changed)
                cameraDetector?.stop()
                Log.d(TAG, "Creating camera detector (selection=$cameraSelection, sensitivity=$cameraSensitivity)")
                cameraDetector =
                    MotionDetector(
                        this,
                        cameraSensitivity,
                        cameraSelector,
                        onMotionDetected = { wakeScreen() },
                        onLevelUpdate = { level, threshold ->
                            broadcastSensorUpdate(SENSOR_CAMERA, level.toFloat(), threshold.toFloat())
                        },
                    )
                cameraDetector?.start()
                currentCameraSelection = cameraSelection
            } else {
                // Same camera, just update sensitivity
                Log.d(TAG, "Updating camera sensitivity to $cameraSensitivity")
                cameraDetector?.updateSensitivity(cameraSensitivity)
            }
        }

        // Update light detection
        if (lightSensitivity == 0) {
            // Stop light detector if disabled
            lightDetector?.let {
                Log.d(TAG, "Stopping light detector")
                it.stop()
                lightDetector = null
            }
        } else {
            // Light is enabled - create or update detector
            if (lightDetector == null) {
                Log.d(TAG, "Creating light detector (sensitivity=$lightSensitivity)")
                lightDetector =
                    LightDetector(
                        this,
                        lightSensitivity,
                        onLightChangeDetected = { wakeScreen() },
                        onLevelUpdate = { level, threshold ->
                            broadcastSensorUpdate(SENSOR_LIGHT, level, threshold)
                        },
                    )
                lightDetector?.start()
            } else {
                Log.d(TAG, "Updating light sensitivity to $lightSensitivity")
                lightDetector?.updateSensitivity(lightSensitivity)
            }
        }

        // Stop service if all disabled
        val cameraEnabled = cameraSelection != MainActivity.CAMERA_NONE && cameraSensitivity > 0
        val lightEnabled = lightSensitivity > 0
        if (!cameraEnabled && !lightEnabled) {
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
            // Don't auto-open if screen is already on (user is actively using device)
            if (powerManager.isInteractive) {
                Log.d(TAG, "Screen already on, skipping auto-open")
                return
            }

            // CRITICAL FIX: Use acquire with timeout, don't release immediately
            // The timeout will auto-release after WAKE_DURATION_MS
            if (!wakeLock.isHeld) {
                Log.i(TAG, "Waking screen")
                wakeLock.acquire(WAKE_DURATION_MS)
                // Don't call release() - the timeout handles it automatically

                // Play notification sound if configured
                val notificationSoundUri = prefs.getString(MainActivity.KEY_NOTIFICATION_SOUND, "") ?: ""
                if (notificationSoundUri.isNotEmpty()) {
                    try {
                        val ringtone = android.media.RingtoneManager.getRingtone(this, android.net.Uri.parse(notificationSoundUri))
                        ringtone?.play()
                        Log.i(TAG, "Playing notification sound: $notificationSoundUri")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error playing notification sound", e)
                    }
                }

                // Always launch unlock activity to dismiss lock screen and/or launch app
                val launchApp = prefs.getString(MainActivity.KEY_LAUNCH_APP, "") ?: ""
                Log.i(TAG, "Launching unlock activity (app=$launchApp, locked=${keyguardManager.isKeyguardLocked})")
                val intent = Intent(this, UnlockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error waking screen", e)
        }
    }

    private fun broadcastSensorUpdate(
        sensorType: String,
        currentLevel: Float,
        threshold: Float,
    ) {
        val intent =
            Intent(ACTION_SENSOR_UPDATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_SENSOR_TYPE, sensorType)
                putExtra(EXTRA_CURRENT_LEVEL, currentLevel)
                putExtra(EXTRA_THRESHOLD, threshold)
            }
        sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "DetectionService"
        private const val CHANNEL_ID = "detection_service"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_DURATION_MS = 100L
        const val ACTION_SENSOR_UPDATE = "com.observer.effect.SENSOR_UPDATE"
        const val ACTION_ACTIVITY_FOREGROUND = "com.observer.effect.ACTIVITY_FOREGROUND"
        const val ACTION_ACTIVITY_BACKGROUND = "com.observer.effect.ACTIVITY_BACKGROUND"
        const val EXTRA_SENSOR_TYPE = "sensor_type"
        const val EXTRA_CURRENT_LEVEL = "current_level"
        const val EXTRA_THRESHOLD = "threshold"
        const val SENSOR_CAMERA = "camera"
        const val SENSOR_LIGHT = "light"
    }
}
