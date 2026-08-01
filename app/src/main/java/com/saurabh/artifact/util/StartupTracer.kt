package com.saurabh.artifact.util

import android.os.SystemClock

/**
 * Utility for tracking and logging app startup milestones.
 */
object StartupTracer {

    private val start = SystemClock.elapsedRealtime()

    fun mark(stage: String) {
        val elapsed = getElapsed()
        val message = "$stage at ${elapsed}ms"
        ArtifactLogger.d("StartupTrace", message)
    }

    fun getElapsed(): Long = SystemClock.elapsedRealtime() - start
}
