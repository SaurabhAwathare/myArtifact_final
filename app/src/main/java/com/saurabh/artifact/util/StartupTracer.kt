package com.saurabh.artifact.util

import android.os.SystemClock

/**
 * Utility for tracking and logging app startup milestones.
 */
object StartupTracer {

    private val start = SystemClock.elapsedRealtime()

    fun mark(stage: String) {
        val elapsed = SystemClock.elapsedRealtime() - start
        val message = "$stage at ${elapsed}ms"
        ArtifactLogger.d("StartupTrace", message)
    }
}
