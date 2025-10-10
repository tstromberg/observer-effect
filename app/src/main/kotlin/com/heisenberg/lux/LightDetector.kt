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
    private val onLightChangeDetected: () -> Unit
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var previousLux: Float? = null
    private var lastDetectionTime = 0L

    fun start() {
        if (lightSensor == null) {
            Log.w(TAG, "Light sensor not available")
            return
        }
        sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        Log.i(TAG, "Light sensor monitoring started")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        previousLux = null
    }

    fun updateSensitivity(newSensitivity: Int) {
        sensitivity = newSensitivity
    }

    override fun onSensorChanged(event: SensorEvent) {
        val currentLux = event.values[0]

        if (previousLux != null) {
            val change = abs(currentLux - previousLux!!)
            // Higher sensitivity = lower threshold (inverted scale)
            // sensitivity 100 = 1 lux threshold, sensitivity 1 = 100 lux threshold
            val threshold = (101 - sensitivity).toFloat()

            if (change > threshold) {
                val now = System.currentTimeMillis()
                if (now - lastDetectionTime > DETECTION_COOLDOWN_MS) {
                    Log.d(TAG, "Light change detected: ${previousLux}→$currentLux lux (Δ$change, threshold=$threshold)")
                    lastDetectionTime = now
                    onLightChangeDetected()
                }
            }
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
