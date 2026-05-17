package com.saurabh.artifact.util

import android.os.SystemClock
import android.util.Log

/**
 * Utility for tracking and logging app startup milestones.
 */
object StartupTracer {

    private val start = SystemClock.elapsedRealtime()

    fun mark(stage: String) {
        val elapsed = SystemClock.elapsedRealtime() - start
        Log.d("StartupTrace", "$stage at ${elapsed}ms")
    }
}
