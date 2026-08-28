package com.saurabh.artifact.util

import android.os.SystemClock

import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory

/**
 * Utility for tracking and logging app startup milestones.
 */
object StartupTracer {

    private val start = SystemClock.elapsedRealtime()

    fun mark(stage: String) {
        val elapsed = getElapsed()
        ArtifactLogger.d(
            DiagnosticCategory.STARTUP,
            "STARTUP_MARK",
            mapOf("stage" to stage, "elapsedMs" to elapsed)
        )
    }

    fun getElapsed(): Long = SystemClock.elapsedRealtime() - start
}
