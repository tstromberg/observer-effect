package com.observer.effect

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Transparent activity that shows on top of the lock screen and dismisses the keyguard.
 * This activity is launched by DetectionService when motion is detected and unlock is enabled.
 */
class UnlockActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "UnlockActivity created")

        // Show on lock screen and dismiss keyguard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }

        // Launch app if configured
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        val launchApp = prefs.getString(MainActivity.KEY_LAUNCH_APP, "") ?: ""
        if (launchApp.isNotEmpty()) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(launchApp)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    Log.i(TAG, "Launched app: $launchApp")
                } else {
                    Log.w(TAG, "No launch intent found for package: $launchApp")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error launching app: $launchApp", e)
            }
        }

        // Immediately finish - we just needed to dismiss the keyguard and launch app
        finish()
    }

    companion object {
        private const val TAG = "UnlockActivity"
    }
}
