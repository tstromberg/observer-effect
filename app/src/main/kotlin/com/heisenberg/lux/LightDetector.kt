package com.heisenberg.lux

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs

class LightDetector(
    private val context: Context,
    private var sensitivity: Int,
    private val onLightChangeDetected: () -> Unit,
    private val onLevelUpdate: (Float, Float) -> Unit = { _, _ -> },
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var previousLux: Float? = null
    private var lastDetectionTime = 0L
    private var lastSensorEventTime = 0L
    private var isPaused = false
    private var isListening = false
    private val handler = Handler(Looper.getMainLooper())

    // Runnable to broadcast zero change when sensor is stable
    private val stabilityCheckRunnable =
        object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                // If no sensor event in last 200ms, broadcast zero change
                if (now - lastSensorEventTime > STABILITY_CHECK_MS) {
                    val threshold =
                        if (sensitivity == 0) {
                            Float.MAX_VALUE
                        } else {
                            (sensitivity * 0.05f).coerceIn(0.05f, 5f)
                        }
                    onLevelUpdate(0f, threshold)
                }
                // Schedule next check
                if (isListening && !isPaused) {
                    handler.postDelayed(this, STABILITY_CHECK_MS)
                }
            }
        }

    fun start() {
        if (lightSensor == null) {
            Log.w(TAG, "Light sensor not available")
            return
        }
        // Use SENSOR_DELAY_FASTEST for maximum responsiveness
        // This gives us the fastest updates the hardware can provide
        sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_FASTEST)
        isListening = true
        lastSensorEventTime = System.currentTimeMillis()
        // Start stability check loop
        handler.postDelayed(stabilityCheckRunnable, STABILITY_CHECK_MS)
        Log.i(TAG, "Light sensor monitoring started")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(stabilityCheckRunnable)
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
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_FASTEST)
            isListening = true
            lastSensorEventTime = System.currentTimeMillis()
            // Restart stability check loop
            handler.postDelayed(stabilityCheckRunnable, STABILITY_CHECK_MS)
            Log.i(TAG, "Light sensor detection resumed")
        }
    }

    fun updateSensitivity(newSensitivity: Int) {
        sensitivity = newSensitivity
    }

    override fun onSensorChanged(event: SensorEvent) {
        try {
            if (event.values.isEmpty()) {
                Log.w(TAG, "Received sensor event with no values")
                return
            }
            val currentLux = event.values[0]

            // Calculate threshold
            val threshold =
                if (sensitivity == 0) {
                    Float.MAX_VALUE // Disabled
                } else {
                    // Linear: sensitivity 1 = 0.05 lux (most sensitive), sensitivity 100 = 5.0 lux (least sensitive)
                    // Formula: sensitivity * 0.05
                    (sensitivity * 0.05f).coerceIn(0.05f, 5f)
                }

            // Update last sensor event time for stability check
            lastSensorEventTime = System.currentTimeMillis()

            previousLux?.let { prevLux ->
                val change = abs(currentLux - prevLux)

                // Always broadcast current level (even if 0)
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
                onLevelUpdate(0f, threshold)
            }

            // Always update previousLux to current reading so next event shows delta from this one
            previousLux = currentLux
        } catch (e: Exception) {
            Log.e(TAG, "Error processing sensor event", e)
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor,
        accuracy: Int,
    ) {
        Log.d(TAG, "Sensor accuracy changed: $accuracy")
    }

    companion object {
        private const val TAG = "LightDetector"
        private const val DETECTION_COOLDOWN_MS = 2000L
        private const val STABILITY_CHECK_MS = 200L // Check every 200ms if sensor has gone stable
    }
}
