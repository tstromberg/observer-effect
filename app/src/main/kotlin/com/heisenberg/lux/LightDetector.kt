package com.heisenberg.lux

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs

class LightDetector(
    private val context: Context,
    private var sensitivity: Int,
    private val onLightChangeDetected: () -> Unit,
    private val onLevelUpdate: (Float, Float) -> Unit = { _, _ -> }
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var previousLux: Float? = null
    private var lastDetectionTime = 0L
    private var isPaused = false
    private var isListening = false

    fun start() {
        if (lightSensor == null) {
            Log.w(TAG, "Light sensor not available")
            return
        }
        // Use SENSOR_DELAY_UI for good responsiveness without excessive CPU usage
        // UI delay is ~60ms between updates, which is plenty fast for motion detection
        sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_UI)
        isListening = true
        Log.i(TAG, "Light sensor monitoring started")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        previousLux = null
        isListening = false
        isPaused = false
    }

    fun pause() {
        if (!isPaused && isListening) {
            isPaused = true
            sensorManager.unregisterListener(this)
            isListening = false
            Log.i(TAG, "Light sensor detection paused")
        }
    }

    fun resume() {
        if (isPaused && !isListening && lightSensor != null) {
            isPaused = false
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_UI)
            isListening = true
            Log.i(TAG, "Light sensor detection resumed")
        }
    }

    fun updateSensitivity(newSensitivity: Int) {
        sensitivity = newSensitivity
    }

    override fun onSensorChanged(event: SensorEvent) {
        val currentLux = event.values[0]

        previousLux?.let { prevLux ->
            val change = abs(currentLux - prevLux)

            // Simple linear curve: Real-world light changes are typically 0-10 lux indoors
            // sensitivity 0 = disabled, sensitivity 1 = 10 lux, sensitivity 100 = 0.1 lux
            val threshold = if (sensitivity == 0) {
                Float.MAX_VALUE  // Disabled
            } else {
                // Linear: 10.1 - (sensitivity * 0.1) = range from 10.0 to 0.1
                (10.1f - (sensitivity * 0.1f)).coerceAtLeast(0.1f)
            }

            // Broadcast current level
            onLevelUpdate(change, threshold)

            if (change > threshold) {
                val now = System.currentTimeMillis()
                if (now - lastDetectionTime > DETECTION_COOLDOWN_MS) {
                    Log.d(TAG, "Light change detected: $prevLux→$currentLux lux (Δ$change, threshold=$threshold)")
                    lastDetectionTime = now
                    onLightChangeDetected()
                }
            }
        } ?: run {
            // First reading - broadcast current level with zero change
            val threshold = if (sensitivity == 0) {
                Float.MAX_VALUE
            } else {
                (10.1f - (sensitivity * 0.1f)).coerceAtLeast(0.1f)
            }
            onLevelUpdate(0f, threshold)
        }

        previousLux = currentLux
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Not used
    }

    companion object {
        private const val TAG = "LightDetector"
        private const val DETECTION_COOLDOWN_MS = 2000L
    }
}
