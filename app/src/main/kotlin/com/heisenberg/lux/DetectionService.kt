package com.heisenberg.lux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleOwner

class DetectionService : Service(), LifecycleOwner {
    private val serviceLifecycleProvider = ServiceLifecycleProvider()
    override val lifecycle
        get() = serviceLifecycleProvider.lifecycle
    private lateinit var prefs: SharedPreferences
    private lateinit var wakeLock: PowerManager.WakeLock
    private var motionDetector: MotionDetector? = null
    private var lightDetector: LightDetector? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            MainActivity.KEY_MOTION_SENSITIVITY, MainActivity.KEY_LIGHT_SENSITIVITY -> {
                updateDetectors()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceLifecycleProvider.onStart()
        Log.i(TAG, "Service created")

        prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "HeisenbergLux::WakeLock"
        )

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        updateDetectors()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceLifecycleProvider.onDestroy()
        Log.i(TAG, "Service destroyed")
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        motionDetector?.stop()
        lightDetector?.stop()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateDetectors() {
        val motionSensitivity = prefs.getInt(MainActivity.KEY_MOTION_SENSITIVITY, 0)
        val lightSensitivity = prefs.getInt(MainActivity.KEY_LIGHT_SENSITIVITY, 0)

        // Update motion detection
        if (motionSensitivity > 0) {
            if (motionDetector == null) {
                motionDetector = MotionDetector(this, motionSensitivity) { wakeScreen() }
                motionDetector?.start()
            } else {
                motionDetector?.updateSensitivity(motionSensitivity)
            }
        } else {
            motionDetector?.stop()
            motionDetector = null
        }

        // Update light detection
        if (lightSensitivity > 0) {
            if (lightDetector == null) {
                lightDetector = LightDetector(this, lightSensitivity) { wakeScreen() }
                lightDetector?.start()
            } else {
                lightDetector?.updateSensitivity(lightSensitivity)
            }
        } else {
            lightDetector?.stop()
            lightDetector = null
        }

        // Stop service if both disabled
        if (motionSensitivity == 0 && lightSensitivity == 0) {
            stopSelf()
        }
    }

    private fun wakeScreen() {
        try {
            if (!wakeLock.isHeld) {
                Log.i(TAG, "Waking screen")
                wakeLock.acquire(WAKE_DURATION_MS)
                wakeLock.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error waking screen", e)
        }
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
    }
}
