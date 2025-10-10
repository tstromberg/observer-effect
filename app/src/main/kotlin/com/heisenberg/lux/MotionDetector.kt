package com.heisenberg.lux

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlin.math.abs

class MotionDetector(
    private val context: Context,
    private var sensitivity: Int,
    private val cameraSelector: CameraSelector,
    private val onMotionDetected: () -> Unit,
    private val onLevelUpdate: (Long, Long) -> Unit = { _, _ -> }
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var executor: java.util.concurrent.ExecutorService? = null
    private var previousFrame: ByteArray? = null
    private var lastDetectionTime = 0L
    private var isInitialFrame = true

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted")
            return
        }

        // Create executor if not already exists
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor()
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageAnalysis = null
        previousFrame = null
        isInitialFrame = true
        // CRITICAL FIX: Shutdown executor to prevent thread leak
        executor?.shutdown()
        executor = null
    }

    fun updateSensitivity(newSensitivity: Int) {
        sensitivity = newSensitivity
    }

    private fun bindCamera() {
        val cameraProvider = cameraProvider ?: return
        val executor = executor ?: return

        // FIX: Check if camera is available before binding
        if (!cameraProvider.hasCamera(cameraSelector)) {
            Log.w(TAG, "Selected camera not available")
            return
        }

        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(executor, ::analyzeFrame)
            }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                context as LifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
            val cameraType = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "rear" else "front"
            Log.i(TAG, "Camera bound successfully ($cameraType)")
        } catch (e: Exception) {
            Log.e(TAG, "Error binding camera", e)
        }
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (image.format != ImageFormat.YUV_420_888) {
            image.close()
            return
        }

        val buffer = image.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        previousFrame?.let { prevFrame ->
            if (data.size == prevFrame.size) {
                val diff = calculateDifference(data, prevFrame)

                // FIXED: Real-world motion diffs are typically 0-200 (you saw max ~130)
                // Exponential curve from 1 to 200 for better UX across full slider range
                // sensitivity 1 = 200 (very insensitive), sensitivity 100 = 1 (very sensitive)
                val threshold = when {
                    sensitivity >= 100 -> 1L
                    else -> {
                        // Exponential curve: 200 * (0.005)^((sensitivity-1)/99)
                        val normalizedSens = (sensitivity - 1) / 99.0
                        (200.0 * Math.pow(0.005, normalizedSens)).toLong()
                    }
                }

                // Broadcast current level
                onLevelUpdate(diff, threshold)

                if (diff > threshold) {
                    val now = System.currentTimeMillis()
                    if (now - lastDetectionTime > DETECTION_COOLDOWN_MS) {
                        Log.d(TAG, "Motion detected: diff=$diff, threshold=$threshold")
                        lastDetectionTime = now
                        onMotionDetected()
                    }
                }
            }
        } ?: run {
            // FIX: First frame - broadcast initial level with zero diff
            if (isInitialFrame) {
                val threshold = when {
                    sensitivity >= 100 -> 1L
                    else -> {
                        val normalizedSens = (sensitivity - 1) / 99.0
                        (200.0 * Math.pow(0.005, normalizedSens)).toLong()
                    }
                }
                onLevelUpdate(0L, threshold)
                isInitialFrame = false
            }
        }

        previousFrame = data
        image.close()
    }

    private fun calculateDifference(current: ByteArray, previous: ByteArray): Long {
        var diff = 0L
        val step = maxOf(1, current.size / 1000) // Sample ~1000 pixels

        for (i in current.indices step step) {
            diff += abs(current[i].toInt() - previous[i].toInt())
        }

        return diff / (current.size / step)
    }

    companion object {
        private const val TAG = "MotionDetector"
        private const val DETECTION_COOLDOWN_MS = 2000L
    }
}
