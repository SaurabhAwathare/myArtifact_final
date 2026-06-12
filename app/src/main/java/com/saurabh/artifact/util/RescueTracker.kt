package com.saurabh.artifact.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

/**
 * Tracks consecutive startup crashes to trigger Rescue Mode.
 * Threshold: 3 crashes within 15 seconds of startup.
 */
class RescueTracker(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "rescue_tracker_prefs"
        private const val KEY_CRASH_COUNT = "crash_count"
        private const val KEY_LAST_CRASH_TIME = "last_crash_time"
        private const val CRASH_THRESHOLD = 3
        
        @Volatile
        private var instance: RescueTracker? = null

        fun getInstance(context: Context): RescueTracker =
            instance ?: synchronized(this) {
                instance ?: RescueTracker(context.applicationContext).also { instance = it }
            }
    }

    /**
     * Increments the crash counter if the current crash is within the startup window.
     */
    fun noteCrash() {
        val now = System.currentTimeMillis()
        val currentCount = prefs.getInt(KEY_CRASH_COUNT, 0)

        // If the crash happened significantly after the last one, it's not a loop
        // but if it's within the grace period or if it's the first one, we count it.
        // Actually, we count it if the app crashed *this* launch before the success signal was sent.
        
        prefs.edit(commit = true) {
            putInt(KEY_CRASH_COUNT, currentCount + 1)
            putLong(KEY_LAST_CRASH_TIME, now)
        }
        
        Log.e("RescueTracker", "Crash noted. Count: ${currentCount + 1}")
    }

    /**
     * Checks if the app should enter Rescue Mode.
     */
    fun isRescueModeRequired(): Boolean {
        val count = prefs.getInt(KEY_CRASH_COUNT, 0)
        val required = count >= CRASH_THRESHOLD
        if (required) {
            Log.w("RescueTracker", "Rescue Mode required! Crash count: $count")
        }
        return required
    }

    /**
     * Resets the crash counter. Called after 15 seconds of successful runtime.
     */
    fun onStartupSuccess() {
        if (prefs.getInt(KEY_CRASH_COUNT, 0) > 0) {
            Log.i("RescueTracker", "Startup success signal received. Resetting crash counter.")
            reset()
        }
    }

    fun reset() {
        prefs.edit { clear() }
    }
}
