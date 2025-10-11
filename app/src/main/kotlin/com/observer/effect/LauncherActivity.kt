package com.observer.effect

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

/**
 * Transparent activity that dismisses the keyguard and launches the target app.
 * This activity is launched from the DetectionService when motion/light is detected.
 */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                )
                Log.i(TAG, "Launching target app: $targetPackage with flags=${launchIntent.flags}")
                startActivity(launchIntent)
                Log.i(TAG, "Successfully launched target app: $targetPackage")
            } else {
                Log.w(
                    TAG,
                    "No launch intent found for package: $targetPackage - app may not be installed or launchable",
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception launching app $targetPackage - missing permissions?", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error launching target app: $targetPackage", e)
        } finally {
            // Always finish this transparent activity
            Log.d(TAG, "Finishing LauncherActivity")
            finish()
        }
    }

    companion object {
        private const val TAG = "LauncherActivity"
        const val EXTRA_TARGET_PACKAGE = "target_package"
    }
}
