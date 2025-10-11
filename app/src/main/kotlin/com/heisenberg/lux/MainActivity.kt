package com.heisenberg.lux

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
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

    private val sensorUpdateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
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

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            if (isGranted) {
                Log.i(TAG, "Camera permission granted")
                updateService()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // On first run, detect and save default camera (but keep sensitivity at 0 for safety)
        val isFirstRun = !prefs.contains(KEY_CAMERA_SELECTION)
        if (isFirstRun) {
            val defaultCamera = getDefaultCamera()
            prefs.edit().putInt(KEY_CAMERA_SELECTION, defaultCamera).apply()
            Log.i(TAG, "First run: detected default camera = $defaultCamera")
        }

        // Load saved values
        val cameraSelection = prefs.getInt(KEY_CAMERA_SELECTION, CAMERA_NONE)
        val cameraSensitivity = prefs.getInt(KEY_CAMERA_SENSITIVITY, 0)
        val lightSensitivity = prefs.getInt(KEY_LIGHT_SENSITIVITY, 0)
        val startAtBoot = prefs.getBoolean(KEY_START_AT_BOOT, false)
        val unlockScreen = prefs.getBoolean(KEY_BYPASS_LOCK_SCREEN, true)
        val launchApp = prefs.getString(KEY_LAUNCH_APP, "") ?: ""

        with(binding) {
            // Setup camera spinner
            val cameraOptions =
                arrayOf(
                    getString(R.string.camera_none),
                    getString(R.string.camera_rear_option),
                    getString(R.string.camera_front_option),
                )
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, cameraOptions)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            cameraSpinner?.adapter = adapter
            cameraSpinner?.setSelection(cameraSelection)

            // Setup camera sensitivity
            cameraSeekBar?.progress = cameraSensitivity
            val cameraThreshold = calculateCameraThreshold(cameraSensitivity)
            val cameraThresholdPercent = ((cameraThreshold / CAMERA_MAX_LEVEL) * 100f).coerceIn(0f, 100f).toInt()
            cameraValue?.text = if (cameraSensitivity == 0) getString(R.string.disabled_caps) else "$cameraThresholdPercent%"

            // Show/hide sensitivity controls based on camera selection
            updateCameraSensitivityVisibility(cameraSelection)

            cameraSpinner?.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
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

            cameraSeekBar?.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        val threshold = calculateCameraThreshold(progress)
                        val thresholdPercent = ((threshold / CAMERA_MAX_LEVEL) * 100f).coerceIn(0f, 100f).toInt()
                        cameraValue?.text = if (progress == 0) getString(R.string.disabled_caps) else "$thresholdPercent%"
                        // Update sensitivity in real-time as user slides
                        if (fromUser) {
                            prefs.edit().putInt(KEY_CAMERA_SENSITIVITY, progress).apply()
                            // Clear live reading when disabled
                            if (progress == 0) {
                                binding.cameraLiveReading?.text = ""
                            }
                            // Restart service immediately to update threshold display
                            updateService()
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        Log.i(TAG, "Camera sensitivity finalized at ${seekBar?.progress}")
                    }
                },
            )

            // Setup light sensor
            lightSeekBar.progress = lightSensitivity
            val lightThreshold = calculateLightThreshold(lightSensitivity)
            val lightThresholdPercent = ((lightThreshold / LIGHT_MAX_LEVEL) * 100f).coerceIn(0f, 100f).toInt()
            lightValue.text = if (lightSensitivity == 0) getString(R.string.disabled_caps) else "$lightThresholdPercent%"

            lightSeekBar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        val threshold = calculateLightThreshold(progress)
                        val thresholdPercent = ((threshold / LIGHT_MAX_LEVEL) * 100f).coerceIn(0f, 100f).toInt()
                        binding.lightValue.text = if (progress == 0) getString(R.string.disabled_caps) else "$thresholdPercent%"
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
                },
            )

            // Setup boot checkbox
            startAtBootCheckbox?.isChecked = startAtBoot
            startAtBootCheckbox?.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(KEY_START_AT_BOOT, isChecked).apply()
                Log.i(TAG, "Start at boot set to $isChecked")
            }

            // Setup unlock screen checkbox
            unlockScreenCheckbox?.isChecked = unlockScreen
            unlockScreenCheckbox?.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(KEY_BYPASS_LOCK_SCREEN, isChecked).apply()
                Log.i(TAG, "Unlock screen set to $isChecked")
            }

            // Setup launch app spinner
            val installedApps = getLaunchableApps()
            val appNames = mutableListOf(getString(R.string.launch_app_none))
            val appPackages = mutableListOf("")
            installedApps.forEach { appInfo ->
                appNames.add(appInfo.loadLabel(packageManager).toString())
                appPackages.add(appInfo.packageName)
            }

            val appAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, appNames)
            appAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            launchAppSpinner?.adapter = appAdapter

            // Set current selection
            val currentIndex = appPackages.indexOf(launchApp).coerceAtLeast(0)
            launchAppSpinner?.setSelection(currentIndex)

            launchAppSpinner?.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        val selectedPackage = appPackages[position]
                        prefs.edit().putString(KEY_LAUNCH_APP, selectedPackage).apply()
                        Log.i(TAG, "Launch app set to $selectedPackage")
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
        }

        // Disable light sensor controls if no sensor is detected
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor == null) {
            // Sensor not available - disable controls and show message
            binding.lightSeekBar.isEnabled = false
            binding.lightSeekBar.alpha = 0.5f
            binding.lightValue.text = getString(R.string.sensor_not_available)
            binding.lightValue.alpha = 0.5f
            binding.lightLiveReading.text = getString(R.string.sensor_not_available_message)
            binding.lightLiveReading.visibility = View.VISIBLE
            Log.i(TAG, "Light sensor not detected on this device")
        }

        // Request camera permission if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

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
        binding.cameraLiveReading?.visibility = if (visible) View.VISIBLE else View.GONE
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
        // Only notify service if it's actually running
        if (isServiceRunning()) {
            val intent =
                Intent(this, DetectionService::class.java).apply {
                    action = DetectionService.ACTION_ACTIVITY_FOREGROUND
                }
            ContextCompat.startForegroundService(this, intent)
            Log.i(TAG, "Activity resumed, notified service")
        }
    }

    override fun onPause() {
        super.onPause()
        // Only notify service if it's actually running
        if (isServiceRunning()) {
            val intent =
                Intent(this, DetectionService::class.java).apply {
                    action = DetectionService.ACTION_ACTIVITY_BACKGROUND
                }
            ContextCompat.startForegroundService(this, intent)
            Log.i(TAG, "Activity paused, notified service")
        }
    }

    private fun isServiceRunning(): Boolean {
        val cameraSelection = prefs.getInt(KEY_CAMERA_SELECTION, CAMERA_NONE)
        val cameraEnabled = cameraSelection != CAMERA_NONE && prefs.getInt(KEY_CAMERA_SENSITIVITY, 0) > 0
        val lightEnabled = prefs.getInt(KEY_LIGHT_SENSITIVITY, 0) > 0
        return cameraEnabled || lightEnabled
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

    private fun updateService() {
        val cameraSelection = prefs.getInt(KEY_CAMERA_SELECTION, CAMERA_NONE)
        val cameraEnabled = cameraSelection != CAMERA_NONE && prefs.getInt(KEY_CAMERA_SENSITIVITY, 0) > 0
        val lightEnabled = prefs.getInt(KEY_LIGHT_SENSITIVITY, 0) > 0

        if (cameraEnabled || lightEnabled) {
            val serviceIntent = Intent(this, DetectionService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            stopService(Intent(this, DetectionService::class.java))
        }
    }

    private fun updateLiveReading(
        sensorType: String,
        currentLevel: Float,
        threshold: Float,
    ) {
        val exceeds = currentLevel > threshold
        val emoji = if (exceeds) "\uD83D\uDCA1" else "\uD83D\uDCA4"

        when (sensorType) {
            DetectionService.SENSOR_CAMERA -> {
                isCameraActive = exceeds
                val cameraSensitivity = prefs.getInt(KEY_CAMERA_SENSITIVITY, 0)
                if (cameraSensitivity == 0) {
                    // Don't update display when disabled
                    binding.cameraValue?.text = getString(R.string.disabled_caps)
                } else {
                    // Convert camera level to percentage (0-200 -> 0-100%)
                    val levelPercent = ((currentLevel / CAMERA_MAX_LEVEL) * 100f).coerceIn(0f, 100f).toInt()
                    val thresholdPercent = ((threshold / CAMERA_MAX_LEVEL) * 100f).coerceIn(0f, 100f).toInt()
                    binding.cameraLiveReading?.text = "$emoji Current activity level: $levelPercent%"
                    // Also update the slider value display with threshold
                    binding.cameraValue?.text = "$thresholdPercent%"
                }
            }
            DetectionService.SENSOR_LIGHT -> {
                isLightActive = exceeds
                val lightSensitivity = prefs.getInt(KEY_LIGHT_SENSITIVITY, 0)
                if (lightSensitivity == 0) {
                    // Don't update display when disabled
                    binding.lightValue.text = getString(R.string.disabled_caps)
                } else {
                    // Convert light level to percentage (0-5 lux -> 0-100%)
                    val levelPercent = ((currentLevel / LIGHT_MAX_LEVEL) * 100f).coerceIn(0f, 100f).toInt()
                    val thresholdPercent = ((threshold / LIGHT_MAX_LEVEL) * 100f).coerceIn(0f, 100f).toInt()
                    binding.lightLiveReading.text = "$emoji Current activity level: $levelPercent%"
                    // Also update the slider value display with threshold
                    binding.lightValue.text = "$thresholdPercent%"
                }
            }
        }

        // Update border based on activity
        val isActive = isCameraActive || isLightActive
        if (isActive) {
            binding.rootContainer.setBackgroundResource(R.drawable.border_yellow)
        } else {
            binding.rootContainer.setBackgroundResource(android.R.color.transparent)
        }
    }

    private fun calculateCameraThreshold(sensitivity: Int): Long {
        return if (sensitivity == 0) {
            Long.MAX_VALUE // Disabled
        } else {
            // Linear mapping: sensitivity 1 = threshold 1 (most sensitive), sensitivity 100 = threshold 200 (least sensitive)
            // Formula: sensitivity * 2 - 1
            ((sensitivity * 2L) - 1L).coerceIn(1L, 200L)
        }
    }

    private fun calculateLightThreshold(sensitivity: Int): Float {
        return if (sensitivity == 0) {
            Float.MAX_VALUE
        } else {
            // Linear: sensitivity 1 = 0.05 lux (most sensitive), sensitivity 100 = 5.0 lux (least sensitive)
            // Formula: sensitivity * 0.05
            (sensitivity * 0.05f).coerceIn(0.05f, 5f)
        }
    }

    private fun getLaunchableApps(): List<ApplicationInfo> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)

        return pm.queryIntentActivities(mainIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName } // Exclude this app
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
    }

    companion object {
        private const val TAG = "MainActivity"
        const val PREFS_NAME = "HeisenbergLuxPrefs"
        const val KEY_CAMERA_SELECTION = "camera_selection"
        const val KEY_CAMERA_SENSITIVITY = "camera_sensitivity"
        const val KEY_LIGHT_SENSITIVITY = "light_sensitivity"
        const val KEY_START_AT_BOOT = "start_at_boot"
        const val KEY_BYPASS_LOCK_SCREEN = "bypass_lock_screen"
        const val KEY_LAUNCH_APP = "launch_app"

        const val CAMERA_NONE = 0
        const val CAMERA_REAR = 1
        const val CAMERA_FRONT = 2

        // Maximum values for percentage calculation
        private const val CAMERA_MAX_LEVEL = 200f // Camera threshold max is 200
        private const val LIGHT_MAX_LEVEL = 5f // Light sensor max observed ~4.6 lux, using 5 for headroom
    }
}
