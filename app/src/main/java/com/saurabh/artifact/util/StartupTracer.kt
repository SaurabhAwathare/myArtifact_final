package com.saurabh.artifact.util

import android.os.SystemClock
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Utility for tracking and logging app startup milestones.
 */
object StartupTracer {

    private val start = SystemClock.elapsedRealtime()

    fun mark(stage: String) {
        val elapsed = SystemClock.elapsedRealtime() - start
        val message = "$stage at ${elapsed}ms"
        Log.d("StartupTrace", message)
        try {
            FirebaseCrashlytics.getInstance().log(message)
        } catch (e: Exception) {
            // Crashlytics might not be initialized yet in some edge cases
        }
    }
}
