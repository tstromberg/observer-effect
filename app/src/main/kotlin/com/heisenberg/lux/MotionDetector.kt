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
    private val onMotionDetected: () -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var previousFrame: ByteArray? = null
    private var lastDetectionTime = 0L

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted")
            return
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
    }

    fun updateSensitivity(newSensitivity: Int) {
        sensitivity = newSensitivity
    }

    private fun bindCamera() {
        val cameraProvider = cameraProvider ?: return

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
                CameraSelector.DEFAULT_BACK_CAMERA,
                imageAnalysis
            )
            Log.i(TAG, "Camera bound successfully")
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

        if (previousFrame != null && data.size == previousFrame!!.size) {
            val diff = calculateDifference(data, previousFrame!!)
            val threshold = (100 - sensitivity) * 10 // Higher sensitivity = lower threshold

            if (diff > threshold) {
                val now = System.currentTimeMillis()
                if (now - lastDetectionTime > DETECTION_COOLDOWN_MS) {
                    Log.d(TAG, "Motion detected: diff=$diff, threshold=$threshold")
                    lastDetectionTime = now
                    onMotionDetected()
                }
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
