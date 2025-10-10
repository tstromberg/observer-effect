package com.heisenberg.lux

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var motionSeekBar: SeekBar
    private lateinit var lightSeekBar: SeekBar
    private lateinit var motionValue: TextView
    private lateinit var lightValue: TextView
    private lateinit var cameraInfo: TextView
    private lateinit var lightSensorInfo: TextView

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "Camera permission granted")
            updateCameraInfo()
            updateService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        motionSeekBar = findViewById(R.id.motionSeekBar)
        lightSeekBar = findViewById(R.id.lightSeekBar)
        motionValue = findViewById(R.id.motionValue)
        lightValue = findViewById(R.id.lightValue)
        cameraInfo = findViewById(R.id.cameraInfo)
        lightSensorInfo = findViewById(R.id.lightSensorInfo)

        // Load saved values
        val motionSensitivity = prefs.getInt(KEY_MOTION_SENSITIVITY, 0)
        val lightSensitivity = prefs.getInt(KEY_LIGHT_SENSITIVITY, 0)

        motionSeekBar.progress = motionSensitivity
        lightSeekBar.progress = lightSensitivity
        motionValue.text = if (motionSensitivity == 0) getString(R.string.disabled) else motionSensitivity.toString()
        lightValue.text = if (lightSensitivity == 0) getString(R.string.disabled) else lightSensitivity.toString()

        motionSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                motionValue.text = if (progress == 0) getString(R.string.disabled) else progress.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                prefs.edit().putInt(KEY_MOTION_SENSITIVITY, progress).apply()
                Log.i(TAG, "Motion sensitivity set to $progress")
                updateService()
            }
        })

        lightSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                lightValue.text = if (progress == 0) getString(R.string.disabled) else progress.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                prefs.edit().putInt(KEY_LIGHT_SENSITIVITY, progress).apply()
                Log.i(TAG, "Light sensitivity set to $progress")
                updateService()
            }
        })

        updateCameraInfo()
        updateLightSensorInfo()
        requestCameraPermissionIfNeeded()
    }

    private fun requestCameraPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun updateCameraInfo() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val facingStr = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "Rear"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    else -> "External"
                }
                cameraInfo.text = "$facingStr Camera"
            } else {
                cameraInfo.text = getString(R.string.not_detected)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading camera info", e)
            cameraInfo.text = getString(R.string.not_detected)
        }
    }

    private fun updateLightSensorInfo() {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        lightSensorInfo.text = if (lightSensor != null) {
            "Light Sensor (${lightSensor.maximumRange.toInt()} lux max)"
        } else {
            getString(R.string.not_detected)
        }
    }

    private fun updateService() {
        val motionEnabled = prefs.getInt(KEY_MOTION_SENSITIVITY, 0) > 0
        val lightEnabled = prefs.getInt(KEY_LIGHT_SENSITIVITY, 0) > 0

        if (motionEnabled || lightEnabled) {
            val serviceIntent = Intent(this, DetectionService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            stopService(Intent(this, DetectionService::class.java))
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        const val PREFS_NAME = "HeisenbergLuxPrefs"
        const val KEY_MOTION_SENSITIVITY = "motion_sensitivity"
        const val KEY_LIGHT_SENSITIVITY = "light_sensitivity"
    }
}
