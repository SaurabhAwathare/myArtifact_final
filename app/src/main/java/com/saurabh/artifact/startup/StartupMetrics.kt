package com.saurabh.artifact.startup

import android.util.Log
// import com.google.firebase.perf.FirebasePerformance
// import com.google.firebase.perf.metrics.Trace

object StartupMetrics {
    private var appCreateTime: Long = 0
    private var authReadyTime: Long = 0
    private var feedHydrationStartTime: Long = 0
    private var firstArtifactRenderTime: Long = 0

    // private var startupTrace: Trace? = null

    fun onAppCreate() {
        appCreateTime = System.currentTimeMillis()
        Log.i("STARTUP_METRICS", "App Created at $appCreateTime")

        // DIAGNOSTIC: Disabled Firebase Performance to isolate SecurityException/Resource 6a error
        Log.d("STARTUP_METRICS", "DIAGNOSTIC: Firebase Performance initialization SKIPPED")
        /*
        try {
            startupTrace = FirebasePerformance.getInstance().newTrace("startup_flow")
            startupTrace?.start()
        } catch (e: Exception) {
            Log.e("STARTUP_METRICS", "Failed to start Firebase trace", e)
        }
        */
    }

    fun onAuthReady() {
        if (authReadyTime != 0L) return
        authReadyTime = System.currentTimeMillis()
        val duration = authReadyTime - appCreateTime
        Log.i("STARTUP_METRICS", "Auth Ready in ${duration}ms")

        // Tag the trace with this milestone
        // startupTrace?.putAttribute("auth_ready_ms", duration.toString())
    }

    fun onFeedHydrationStart() {
        if (feedHydrationStartTime != 0L) return
        feedHydrationStartTime = System.currentTimeMillis()
        Log.i("STARTUP_METRICS", "Feed Hydration Started at $feedHydrationStartTime")
    }

    fun onFirstArtifactRendered() {
        if (firstArtifactRenderTime != 0L) return
        firstArtifactRenderTime = System.currentTimeMillis()
        val totalDuration = firstArtifactRenderTime - appCreateTime
        val hydrationDuration = firstArtifactRenderTime - feedHydrationStartTime
        Log.i("STARTUP_METRICS", "First Artifact Rendered in ${totalDuration}ms (Total)")
        Log.i("STARTUP_METRICS", "Feed Hydration took ${hydrationDuration}ms")

        // Finalize metrics and stop the trace
        /*
        startupTrace?.apply {
            putAttribute("total_ms", totalDuration.toString())
            putAttribute("hydration_ms", hydrationDuration.toString())
            stop()
        }
        startupTrace = null
        */
    }
}
