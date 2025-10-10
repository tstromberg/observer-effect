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

        // Load saved values
        val cameraSelection = prefs.getInt(KEY_CAMERA_SELECTION, CAMERA_NONE)
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
            cameraValue?.text = cameraSensitivity.toString()

            // Show/hide sensitivity controls based on camera selection
            updateCameraSensitivityVisibility(cameraSelection)

            cameraSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.edit().putInt(KEY_CAMERA_SELECTION, position).apply()
                    Log.i(TAG, "Camera selection changed to $position")
                    updateCameraSensitivityVisibility(position)
                    updateService()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            cameraSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    cameraValue?.text = progress.toString()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val progress = seekBar?.progress ?: 50
                    prefs.edit().putInt(KEY_CAMERA_SENSITIVITY, progress).apply()
                    Log.i(TAG, "Camera sensitivity set to $progress")
                    updateService()
                }
            })

            // Setup light sensor
            lightSeekBar?.progress = lightSensitivity
            lightValue?.text = if (lightSensitivity == 0) getString(R.string.disabled) else lightSensitivity.toString()

            lightSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    binding.lightValue?.text = if (progress == 0) getString(R.string.disabled) else progress.toString()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val progress = seekBar?.progress ?: 0
                    prefs.edit().putInt(KEY_LIGHT_SENSITIVITY, progress).apply()
                    Log.i(TAG, "Light sensitivity set to $progress")
                    updateService()
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
        updateLightSensorInfo()
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
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) {
                binding.cameraInfo.text = getString(R.string.not_detected)
                return
            }

            val cameras = mutableListOf<String>()
            for (cameraId in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> cameras.add(getString(R.string.camera_rear))
                    CameraCharacteristics.LENS_FACING_FRONT -> cameras.add(getString(R.string.camera_front))
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> cameras.add(getString(R.string.camera_external))
                }
            }

            binding.cameraInfo.text = if (cameras.isNotEmpty()) {
                val separator = getString(R.string.camera_separator)
                val suffix = if (cameras.size > 1) {
                    getString(R.string.camera_suffix_plural)
                } else {
                    getString(R.string.camera_suffix)
                }
                cameras.joinToString(separator) + " " + suffix
            } else {
                getString(R.string.not_detected)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading camera info", e)
            binding.cameraInfo.text = getString(R.string.not_detected)
        }
    }

    private fun updateLightSensorInfo() {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        binding.lightSensorInfo.text = if (lightSensor != null) {
            getString(R.string.light_sensor_format, lightSensor.maximumRange.toInt())
        } else {
            getString(R.string.not_detected)
        }
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
        val text = "$emoji ${getString(R.string.level_format, currentLevel.toInt(), threshold.toInt())}"

        when (sensorType) {
            DetectionService.SENSOR_CAMERA -> binding.cameraLiveReading?.text = text
            DetectionService.SENSOR_LIGHT -> binding.lightLiveReading?.text = text
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
