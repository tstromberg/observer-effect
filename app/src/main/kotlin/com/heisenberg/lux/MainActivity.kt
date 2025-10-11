package com.heisenberg.lux

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.heisenberg.lux.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var isReceiverRegistered = false
    private var isCameraActive = false
    private var isLightActive = false

    private val sensorUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DetectionService.ACTION_SENSOR_UPDATE) {
                val sensorType = intent.getStringExtra(DetectionService.EXTRA_SENSOR_TYPE) ?: return
                val currentLevel = intent.getFloatExtra(DetectionService.EXTRA_CURRENT_LEVEL, 0f)
                val threshold = intent.getFloatExtra(DetectionService.EXTRA_THRESHOLD, 0f)
                runOnUiThread {
                    updateLiveReading(sensorType, currentLevel, threshold)
                }
            }
        }
    }

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Load saved values (default to front camera if this is first run)
        val cameraSelection = prefs.getInt(KEY_CAMERA_SELECTION, getDefaultCamera())
        val cameraSensitivity = prefs.getInt(KEY_CAMERA_SENSITIVITY, 50)
        val lightSensitivity = prefs.getInt(KEY_LIGHT_SENSITIVITY, 0)
        val startAtBoot = prefs.getBoolean(KEY_START_AT_BOOT, false)

        with(binding) {
            // Setup camera spinner
            val cameraOptions = arrayOf(
                getString(R.string.camera_none),
                getString(R.string.camera_rear_option),
                getString(R.string.camera_front_option)
            )
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, cameraOptions)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            cameraSpinner?.adapter = adapter
            cameraSpinner?.setSelection(cameraSelection)

            // Setup camera sensitivity
            cameraSeekBar?.progress = cameraSensitivity
            cameraValue?.text = calculateCameraThreshold(cameraSensitivity).toString()

            // Show/hide sensitivity controls based on camera selection
            updateCameraSensitivityVisibility(cameraSelection)

            cameraSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.edit().putInt(KEY_CAMERA_SELECTION, position).apply()
                    Log.i(TAG, "Camera selection changed to $position")
                    updateCameraSensitivityVisibility(position)
                    // Clear live reading when camera is disabled
                    if (position == CAMERA_NONE) {
                        binding.cameraLiveReading?.text = ""
                    }
                    updateService()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            cameraSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    cameraValue?.text = calculateCameraThreshold(progress).toString()
                    // Update sensitivity in real-time as user slides
                    if (fromUser) {
                        prefs.edit().putInt(KEY_CAMERA_SENSITIVITY, progress).apply()
                        // Restart service immediately to update threshold display
                        updateService()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    Log.i(TAG, "Camera sensitivity finalized at ${seekBar?.progress}")
                }
            })

            // Setup light sensor
            lightSeekBar?.progress = lightSensitivity
            lightValue?.text = if (lightSensitivity == 0) getString(R.string.disabled_caps) else "%.1f".format(calculateLightThreshold(lightSensitivity))

            lightSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    binding.lightValue.text = if (progress == 0) getString(R.string.disabled_caps) else "%.1f".format(calculateLightThreshold(progress))
                    // Update sensitivity in real-time as user slides
                    if (fromUser) {
                        prefs.edit().putInt(KEY_LIGHT_SENSITIVITY, progress).apply()
                        // Clear live reading when disabled
                        if (progress == 0) {
                            binding.lightLiveReading.text = ""
                        }
                        // Restart service immediately to update threshold display
                        updateService()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val progress = seekBar?.progress ?: 0
                    Log.i(TAG, "Light sensitivity finalized at $progress")
                }
            })

            // Setup boot checkbox
            startAtBootCheckbox?.isChecked = startAtBoot
            startAtBootCheckbox?.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(KEY_START_AT_BOOT, isChecked).apply()
                Log.i(TAG, "Start at boot set to $isChecked")
            }
        }

        updateCameraInfo()
        updateLightSensorVisibility()
        requestCameraPermissionIfNeeded()

        // Register broadcast receiver for sensor updates
        val filter = IntentFilter(DetectionService.ACTION_SENSOR_UPDATE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(sensorUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
                // FIX: Set flag IMMEDIATELY after successful registration
                isReceiverRegistered = true
            } else {
                registerReceiver(sensorUpdateReceiver, filter)
                // FIX: Set flag IMMEDIATELY after successful registration
                isReceiverRegistered = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receiver", e)
            isReceiverRegistered = false
        }
    }

    private fun updateCameraSensitivityVisibility(cameraSelection: Int) {
        val visible = cameraSelection != CAMERA_NONE
        binding.cameraSensitivityLabel?.visibility = if (visible) View.VISIBLE else View.GONE
        binding.cameraSensitivityContainer?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun getDefaultCamera(): Int {
        // Default to front camera if available, otherwise rear, otherwise none
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val cameraIds = cameraManager.cameraIdList
            var hasFront = false
            var hasRear = false

            for (cameraId in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> hasFront = true
                    CameraCharacteristics.LENS_FACING_BACK -> hasRear = true
                }
            }

            when {
                hasFront -> CAMERA_FRONT
                hasRear -> CAMERA_REAR
                else -> CAMERA_NONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting default camera", e)
            CAMERA_NONE
        }
    }

    override fun onResume() {
        super.onResume()
        // Notify service that MainActivity is in foreground
        val intent = Intent(this, DetectionService::class.java).apply {
            action = DetectionService.ACTION_ACTIVITY_FOREGROUND
        }
        ContextCompat.startForegroundService(this, intent)
        Log.i(TAG, "Activity resumed, notified service")
    }

    override fun onPause() {
        super.onPause()
        // Notify service that MainActivity is in background
        val intent = Intent(this, DetectionService::class.java).apply {
            action = DetectionService.ACTION_ACTIVITY_BACKGROUND
        }
        ContextCompat.startForegroundService(this, intent)
        Log.i(TAG, "Activity paused, notified service")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(sensorUpdateReceiver)
                isReceiverRegistered = false
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver not registered or already unregistered")
            }
        }
    }

    private fun requestCameraPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun updateCameraInfo() {
        // Camera info is no longer displayed, but we keep this method
        // in case we need to add camera detection logic in the future
    }

    private fun updateLightSensorVisibility() {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        // Hide the entire light sensor card if no sensor is detected
        binding.lightCard.visibility = if (lightSensor != null) View.VISIBLE else View.GONE
    }

    private fun updateService() {
        val cameraSelection = prefs.getInt(KEY_CAMERA_SELECTION, CAMERA_NONE)
        val lightEnabled = prefs.getInt(KEY_LIGHT_SENSITIVITY, 0) > 0

        if (cameraSelection != CAMERA_NONE || lightEnabled) {
            val serviceIntent = Intent(this, DetectionService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            stopService(Intent(this, DetectionService::class.java))
        }
    }

    private fun updateLiveReading(sensorType: String, currentLevel: Float, threshold: Float) {
        val exceeds = currentLevel > threshold
        val emoji = if (exceeds) "\uD83D\uDCA1" else "\uD83D\uDCA4"
        // Format with 1 decimal place for better precision
        val text = "$emoji Detected Level: %.1f".format(currentLevel)

        when (sensorType) {
            DetectionService.SENSOR_CAMERA -> {
                isCameraActive = exceeds
                binding.cameraLiveReading?.text = text
                // Also update the slider value display with threshold
                binding.cameraValue?.text = "%.0f".format(threshold)
            }
            DetectionService.SENSOR_LIGHT -> {
                isLightActive = exceeds
                binding.lightLiveReading?.text = text
                // Also update the slider value display with threshold
                binding.lightValue?.text = "%.1f".format(threshold)
            }
        }

        // Update border based on activity
        updateBorder()
    }

    private fun updateBorder() {
        val isActive = isCameraActive || isLightActive

        if (isActive) {
            // Show yellow border immediately when activity detected
            binding.rootContainer.setBackgroundResource(R.drawable.border_yellow)
        } else {
            // Remove border immediately when activity stops (💤 appears)
            binding.rootContainer.setBackgroundResource(android.R.color.transparent)
        }
    }

    private fun calculateCameraThreshold(sensitivity: Int): Long {
        return when {
            sensitivity >= 100 -> 1L
            else -> {
                // Exponential curve: 200 * (0.005)^((sensitivity-1)/99)
                val normalizedSens = (sensitivity - 1) / 99.0
                (200.0 * Math.pow(0.005, normalizedSens)).toLong()
            }
        }
    }

    private fun calculateLightThreshold(sensitivity: Int): Float {
        return if (sensitivity == 0) {
            Float.MAX_VALUE
        } else {
            // Linear: 10.1 - (sensitivity * 0.1) = range from 10.0 to 0.1
            (10.1f - (sensitivity * 0.1f)).coerceAtLeast(0.1f)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        const val PREFS_NAME = "HeisenbergLuxPrefs"
        const val KEY_CAMERA_SELECTION = "camera_selection"
        const val KEY_CAMERA_SENSITIVITY = "camera_sensitivity"
        const val KEY_LIGHT_SENSITIVITY = "light_sensitivity"
        const val KEY_START_AT_BOOT = "start_at_boot"

        const val CAMERA_NONE = 0
        const val CAMERA_REAR = 1
        const val CAMERA_FRONT = 2
    }
}
