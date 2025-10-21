package com.observer.effect

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager

/**
 * Transparent activity that dismisses the keyguard and launches the target app.
 * This activity is launched from the DetectionService when motion/light is detected.
 */
class LauncherActivity : Activity() {
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable =
        Runnable {
            Log.w(TAG, "Timeout reached, finishing LauncherActivity (target app may not have launched)")
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Disable activity transitions for instant appearance
        overridePendingTransition(0, 0)

        // Safety timeout: finish after timeout period if onStop() not called
        // This prevents hanging if target app never comes to foreground
        timeoutHandler.postDelayed(timeoutRunnable, FINISH_TIMEOUT_MS)

        // Acquire a wake lock to ensure the screen turns on reliably
        // This is more robust than just relying on window flags
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock =
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ObserverEffect:LauncherWakeLock",
            )
        wakeLock.acquire(1 * 1000L /* 1 second */)

        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        Log.i(TAG, "LauncherActivity started for package: $targetPackage (API ${Build.VERSION.SDK_INT})")

        // Set window flags for API < 27
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        } else {
            // For API 27+, use the Activity methods
            setShowWhenLocked(true)
            setTurnScreenOn(true)

            // Request keyguard dismissal
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (keyguardManager != null) {
                // Check if keyguard is actually locked
                val isKeyguardLocked = keyguardManager.isKeyguardLocked
                Log.d(TAG, "Keyguard locked state: $isKeyguardLocked")

                if (isKeyguardLocked) {
                    // Only request dismissal if keyguard is actually shown
                    keyguardManager.requestDismissKeyguard(
                        this,
                        object : KeyguardManager.KeyguardDismissCallback() {
                            override fun onDismissSucceeded() {
                                Log.i(TAG, "Keyguard dismissed successfully")
                                launchTargetApp()
                            }

                            override fun onDismissError() {
                                Log.e(TAG, "Keyguard dismiss error")
                                launchTargetApp()
                            }

                            override fun onDismissCancelled() {
                                Log.w(TAG, "Keyguard dismiss cancelled")
                                finish()
                            }
                        },
                    )
                } else {
                    // Keyguard not locked, launch app immediately
                    Log.d(TAG, "Keyguard not locked, launching app immediately")
                    launchTargetApp()
                }
            } else {
                Log.e(TAG, "KeyguardManager not available, launching app anyway")
                launchTargetApp()
            }
        }

        // For API < 27, launch after brief delay to ensure flags take effect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            // Post to handler to ensure window flags have been processed
            window.decorView.post {
                launchTargetApp()
            }
        }
    }

    private fun launchTargetApp() {
        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        if (targetPackage.isNullOrEmpty()) {
            Log.w(TAG, "No target package specified, finishing activity")
            finish()
            return
        }

        try {
            Log.d(TAG, "Attempting to resolve launch intent for package: $targetPackage")
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                // Launch the target app and bring it to foreground
                // NEW_TASK: Required to start in its own task
                // RESET_TASK_IF_NEEDED: Brings the task to foreground in proper state
                // REORDER_TO_FRONT: If already running, bring existing task forward (faster)
                // NO_ANIMATION: Skip animations for instant appearance
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
                Log.i(TAG, "Launching target app: $targetPackage with flags=${launchIntent.flags}")
                startActivity(launchIntent)
                Log.i(TAG, "Successfully launched target app: $targetPackage")
                // Don't finish() here - wait for target app to come to foreground
                // This prevents flashing home screen while slow apps (browsers) are initializing
                // onStop() will finish when target app takes foreground
            } else {
                Log.w(
                    TAG,
                    "No launch intent found for package: $targetPackage - app may not be installed or launchable",
                )
                finish()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception launching app $targetPackage - missing permissions?", e)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error launching target app: $targetPackage", e)
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        // Finish when target app comes to foreground (we go to background)
        // This ensures smooth transition even for slow-drawing apps like browsers
        Log.d(TAG, "LauncherActivity stopped (target app in foreground), finishing")
        finish()
        // Disable exit animation for instant disappearance
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel timeout to prevent memory leaks
        timeoutHandler.removeCallbacks(timeoutRunnable)
        // Ensure no exit animation
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "LauncherActivity"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        private const val FINISH_TIMEOUT_MS = 10000L // 10 seconds for slow apps like browsers
    }
}
