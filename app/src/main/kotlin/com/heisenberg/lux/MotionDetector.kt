package com.heisenberg.lux

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.util.Log
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
    private val onLevelUpdate: (Long, Long) -> Unit = { _, _ -> },
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var executor: java.util.concurrent.ExecutorService? = null
    private var previousFrame: ByteArray? = null
    private var lastDetectionTime = 0L
    private var isInitialFrame = true
    private var isPaused = false

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
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
        isPaused = false
        // CRITICAL FIX: Shutdown executor to prevent thread leak
        executor?.shutdown()
        executor = null
    }

    fun pause() {
        if (!isPaused) {
            isPaused = true
            cameraProvider?.unbindAll()
            Log.i(TAG, "Camera detection paused")
        }
    }

    fun resume() {
        if (isPaused) {
            isPaused = false
            bindCamera()
            Log.i(TAG, "Camera detection resumed")
        }
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

        imageAnalysis =
            ImageAnalysis.Builder()
                .setResolutionSelector(
                    androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionFilter { supportedSizes, _ ->
                            // Filter to get closest to 320x240
                            supportedSizes.sortedBy {
                                val width = if (it.width < it.height) it.width else it.height
                                val height = if (it.width < it.height) it.height else it.width
                                kotlin.math.abs(width - 320) + kotlin.math.abs(height - 240)
                            }
                        }
                        .build(),
                )
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
                imageAnalysis,
            )
            val cameraType = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "rear" else "front"
            Log.i(TAG, "Camera bound successfully ($cameraType)")
        } catch (e: Exception) {
            Log.e(TAG, "Error binding camera", e)
        }
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            if (image.format != ImageFormat.YUV_420_888) {
                image.close()
                return
            }

            val buffer = image.planes[0].buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            // Calculate threshold once per frame
            val threshold =
                if (sensitivity == 0) {
                    Long.MAX_VALUE // Disabled
                } else {
                    // Linear mapping: sensitivity 1 = threshold 1 (most sensitive), sensitivity 100 = threshold 200 (least sensitive)
                    // Formula: sensitivity * 2 - 1
                    ((sensitivity * 2L) - 1L).coerceIn(1L, 200L)
                }

            previousFrame?.let { prevFrame ->
                if (data.size == prevFrame.size) {
                    // Calculate frame difference by sampling ~1000 pixels
                    var diff = 0L
                    val step = maxOf(1, data.size / 1000)
                    for (i in data.indices step step) {
                        diff += abs(data[i].toInt() - prevFrame[i].toInt())
                    }
                    diff /= (data.size / step)

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
                // First frame - broadcast initial level with zero diff
                if (isInitialFrame) {
                    onLevelUpdate(0L, threshold)
                    isInitialFrame = false
                }
            }

            previousFrame = data
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame", e)
        } finally {
            image.close()
        }
    }

    companion object {
        private const val TAG = "MotionDetector"
        private const val DETECTION_COOLDOWN_MS = 2000L
    }
}
