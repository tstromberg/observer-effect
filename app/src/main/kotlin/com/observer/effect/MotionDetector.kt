package com.observer.effect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
    private var currentFrameBuffer: ByteArray? = null
    private var lastDetectionTime = 0L
    private var lastFrameProcessedTime = 0L
    private var isInitialFrame = true
    private var isPaused = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watchdogHandler: Handler? = null
    private var watchdogRunnable: Runnable? = null
    private var consecutiveRestarts = 0
    private var wakeLock: PowerManager.WakeLock? = null

    fun start() {
        Log.d(TAG, "start() called, isPaused=$isPaused")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Camera permission not granted")
            return
        }

        // Acquire wake lock to prevent camera hardware from sleeping
        acquireWakeLock()

        // Create executor if not already exists
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor()
            Log.d(TAG, "Created new executor")
        }

        // Start watchdog to monitor camera health
        startWatchdog()

        Log.d(TAG, "Getting camera provider instance...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                Log.d(TAG, "Camera provider loaded, isPaused=$isPaused")
                // Only bind if not paused - detection might have been paused while camera provider was loading
                if (!isPaused) {
                    Log.d(TAG, "Not paused, calling bindCamera()")
                    bindCamera()
                } else {
                    Log.d(TAG, "Paused, skipping bindCamera()")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting camera", e)
                // Schedule retry if camera fails to start
                scheduleRestart()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        stopWatchdog()
        releaseWakeLock()
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageAnalysis = null
        previousFrame = null
        currentFrameBuffer = null
        isInitialFrame = true
        isPaused = false
        consecutiveRestarts = 0
        // CRITICAL FIX: Shutdown executor to prevent thread leak
        executor?.shutdown()
        executor = null
    }

    fun pause() {
        Log.d(TAG, "pause() called, isPaused=$isPaused")
        if (!isPaused) {
            isPaused = true
            // CameraX unbindAll must run on main thread
            mainHandler.post {
                cameraProvider?.unbindAll()
                Log.i(TAG, "Camera detection paused")
            }
            // Reset frame state when pausing
            previousFrame = null
            isInitialFrame = true
        } else {
            Log.d(TAG, "Already paused, ignoring")
        }
    }

    fun resume() {
        Log.d(TAG, "resume() called, isPaused=$isPaused, cameraProvider=${cameraProvider != null}")
        if (isPaused) {
            isPaused = false
            bindCamera()
            Log.i(TAG, "Camera detection resumed")
        } else {
            Log.d(TAG, "Already resumed, ignoring")
        }
    }

    fun updateSensitivity(newSensitivity: Int) {
        sensitivity = newSensitivity
    }

    private fun bindCamera() {
        Log.d(TAG, "bindCamera() called, cameraProvider=${cameraProvider != null}, executor=${executor != null}, isPaused=$isPaused")
        val cameraProvider = cameraProvider ?: run {
            Log.w(TAG, "bindCamera: cameraProvider is null, returning")
            return
        }
        val executor = executor ?: run {
            Log.w(TAG, "bindCamera: executor is null, returning")
            return
        }

        // FIX: Check if camera is available before binding
        if (!cameraProvider.hasCamera(cameraSelector)) {
            Log.w(TAG, "Selected camera not available")
            return
        }

        Log.d(TAG, "Creating ImageAnalysis and binding to lifecycle...")
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
                    Log.d(TAG, "Setting analyzer on ImageAnalysis")
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
            // Reset restart counter on successful bind
            consecutiveRestarts = 0
        } catch (e: Exception) {
            Log.e(TAG, "Error binding camera: ${e.message}", e)
            // Schedule restart on bind failure
            scheduleRestart()
        }
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            // Throttle frame processing to ~5 FPS to reduce CPU usage on low-end devices
            // Motion detection doesn't need high frame rates
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFrameProcessedTime < FRAME_THROTTLE_MS) {
                image.close()
                return
            }
            lastFrameProcessedTime = currentTime
            Log.v(TAG, "analyzeFrame: processing frame at $currentTime")

            if (image.format != ImageFormat.YUV_420_888) {
                image.close()
                return
            }

            val buffer = image.planes[0].buffer
            val bufferSize = buffer.remaining()

            // Reuse buffer instead of allocating new ByteArray every frame
            // This saves ~1.5 MB/sec of allocations (300KB * 5 FPS)
            if (currentFrameBuffer == null || currentFrameBuffer!!.size != bufferSize) {
                currentFrameBuffer = ByteArray(bufferSize)
            }
            val data = currentFrameBuffer!!
            buffer.rewind()
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
                    Log.v(TAG, "Calling onLevelUpdate(diff=$diff, threshold=$threshold)")
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

            // Make a copy so we don't overwrite previousFrame when reusing currentFrameBuffer
            previousFrame = data.copyOf()
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame", e)
        } finally {
            image.close()
        }
    }

    private fun startWatchdog() {
        if (watchdogHandler == null) {
            watchdogHandler = Handler(Looper.getMainLooper())
        }

        watchdogRunnable = Runnable {
            checkCameraHealth()
        }

        // Check camera health every 10 seconds
        watchdogHandler?.postDelayed(watchdogRunnable!!, WATCHDOG_CHECK_INTERVAL_MS)
        Log.d(TAG, "Watchdog started")
    }

    private fun stopWatchdog() {
        watchdogRunnable?.let {
            watchdogHandler?.removeCallbacks(it)
        }
        watchdogRunnable = null
        Log.d(TAG, "Watchdog stopped")
    }

    private fun checkCameraHealth() {
        if (isPaused) {
            // Don't check health when paused, just reschedule
            watchdogHandler?.postDelayed(watchdogRunnable!!, WATCHDOG_CHECK_INTERVAL_MS)
            return
        }

        val timeSinceLastFrame = System.currentTimeMillis() - lastFrameProcessedTime

        if (lastFrameProcessedTime > 0 && timeSinceLastFrame > FRAME_TIMEOUT_MS) {
            Log.w(
                TAG,
                "Camera appears stuck - no frames for ${timeSinceLastFrame}ms (threshold: ${FRAME_TIMEOUT_MS}ms). Restarting camera...",
            )
            restartCamera()
        } else {
            Log.v(TAG, "Camera health check OK (${timeSinceLastFrame}ms since last frame)")
        }

        // Reschedule next check
        watchdogRunnable?.let {
            watchdogHandler?.postDelayed(it, WATCHDOG_CHECK_INTERVAL_MS)
        }
    }

    private fun scheduleRestart() {
        Log.w(TAG, "Scheduling camera restart (attempt ${consecutiveRestarts + 1})")
        mainHandler.postDelayed({
            restartCamera()
        }, RESTART_DELAY_MS)
    }

    private fun restartCamera() {
        consecutiveRestarts++

        if (consecutiveRestarts > MAX_RESTART_ATTEMPTS) {
            Log.e(
                TAG,
                "Camera restart failed after $MAX_RESTART_ATTEMPTS attempts. Giving up to prevent battery drain.",
            )
            consecutiveRestarts = 0
            return
        }

        Log.i(TAG, "Restarting camera (attempt $consecutiveRestarts/$MAX_RESTART_ATTEMPTS)")

        try {
            // Unbind everything
            mainHandler.post {
                cameraProvider?.unbindAll()
            }

            // Reset state
            imageAnalysis = null
            previousFrame = null
            currentFrameBuffer = null
            isInitialFrame = true
            lastFrameProcessedTime = 0L

            // Wait a bit, then rebind
            mainHandler.postDelayed({
                if (!isPaused) {
                    Log.d(TAG, "Rebinding camera after restart")
                    bindCamera()
                    // Reset counter on successful restart
                    consecutiveRestarts = 0
                } else {
                    Log.d(TAG, "Skipping camera rebind - currently paused")
                }
            }, 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "Error during camera restart", e)
            // Try again after delay
            if (consecutiveRestarts <= MAX_RESTART_ATTEMPTS) {
                scheduleRestart()
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock =
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ObserverEffect:MotionDetectorWakeLock",
                )
            wakeLock?.setReferenceCounted(false)
        }

        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
            Log.i(TAG, "Wake lock acquired to keep camera hardware active")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.i(TAG, "Wake lock released")
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "MotionDetector"
        private const val DETECTION_COOLDOWN_MS = 2000L // Minimum time between detections
        private const val FRAME_THROTTLE_MS = 200L // ~5 FPS (1000ms / 5 = 200ms)
        private const val WATCHDOG_CHECK_INTERVAL_MS = 10000L // Check camera health every 10 seconds
        private const val FRAME_TIMEOUT_MS = 15000L // Consider camera stuck if no frames for 15 seconds
        private const val RESTART_DELAY_MS = 2000L // Wait 2 seconds before restarting
        private const val MAX_RESTART_ATTEMPTS = 5 // Maximum restart attempts before giving up
    }
}
