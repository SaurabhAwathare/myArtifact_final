package com.saurabh.artifact.startup

import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace

object StartupMetrics {
    private var appCreateTime: Long = 0
    private var authReadyTime: Long = 0
    private var feedHydrationStartTime: Long = 0
    private var firstArtifactRenderTime: Long = 0

    private var startupTrace: Trace? = null

    fun onAppCreate(context: android.content.Context) {
        appCreateTime = System.currentTimeMillis()
        ArtifactLogger.i(DiagnosticCategory.STARTUP, "APP_CREATED", mapOf("timestamp" to appCreateTime))

        try {
            // Defensive check to ensure Firebase is ready before starting trace
            if (com.google.firebase.FirebaseApp.getApps(context).isNotEmpty()) {
                startupTrace = FirebasePerformance.getInstance().newTrace("startup_flow")
                startupTrace?.start()
            } else {
                ArtifactLogger.w(DiagnosticCategory.STARTUP, "STARTUP_TRACE_SKIPPED_FIREBASE_NOT_READY")
            }
        } catch (e: Exception) {
            ArtifactLogger.e(DiagnosticCategory.STARTUP, "STARTUP_TRACE_START_FAILED", throwable = e)
        }
    }

    fun onAuthReady() {
        if (authReadyTime != 0L) return
        authReadyTime = System.currentTimeMillis()
        val duration = authReadyTime - appCreateTime
        ArtifactLogger.i(DiagnosticCategory.STARTUP, "AUTH_READY", mapOf("durationMs" to duration))

        // Tag the trace with this milestone
        startupTrace?.putAttribute("auth_ready_ms", duration.toString())
    }

    fun onFeedHydrationStart() {
        if (feedHydrationStartTime != 0L) return
        feedHydrationStartTime = System.currentTimeMillis()
        ArtifactLogger.i(DiagnosticCategory.STARTUP, "FEED_HYDRATION_STARTED", mapOf("timestamp" to feedHydrationStartTime))
    }

    fun onFirstArtifactRendered() {
        if (firstArtifactRenderTime != 0L) return
        firstArtifactRenderTime = System.currentTimeMillis()
        val totalDuration = firstArtifactRenderTime - appCreateTime
        val hydrationDuration = firstArtifactRenderTime - feedHydrationStartTime
        ArtifactLogger.i(
            DiagnosticCategory.STARTUP, 
            "FIRST_ARTIFACT_RENDERED", 
            mapOf(
                "totalDurationMs" to totalDuration,
                "hydrationDurationMs" to hydrationDuration
            )
        )

        // Finalize metrics and stop the trace
        startupTrace?.apply {
            putAttribute("total_ms", totalDuration.toString())
            putAttribute("hydration_ms", hydrationDuration.toString())
            stop()
        }
        startupTrace = null
    }
}
