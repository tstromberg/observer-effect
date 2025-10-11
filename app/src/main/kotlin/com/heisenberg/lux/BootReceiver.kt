package com.heisenberg.lux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed, checking if service should start")

            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val startAtBoot = prefs.getBoolean(MainActivity.KEY_START_AT_BOOT, false)
            val cameraSelection = prefs.getInt(MainActivity.KEY_CAMERA_SELECTION, MainActivity.CAMERA_NONE)
            val cameraSensitivity = prefs.getInt(MainActivity.KEY_CAMERA_SENSITIVITY, 50)
            val lightSensitivity = prefs.getInt(MainActivity.KEY_LIGHT_SENSITIVITY, 0)

            val cameraEnabled = cameraSelection != MainActivity.CAMERA_NONE && cameraSensitivity > 0
            val lightEnabled = lightSensitivity > 0

            if (startAtBoot && (cameraEnabled || lightEnabled)) {
                Log.i(TAG, "Starting DetectionService on boot")
                val serviceIntent = Intent(context, DetectionService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                Log.i(TAG, "Service not configured to start on boot or all detectors disabled")
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
