package com.saurabh.artifact.startup

import android.util.Log

object StartupMetrics {
    private var appCreateTime: Long = 0
    private var authReadyTime: Long = 0
    private var feedHydrationStartTime: Long = 0
    private var firstArtifactRenderTime: Long = 0

    fun onAppCreate() {
        appCreateTime = System.currentTimeMillis()
        Log.i("STARTUP_METRICS", "App Created at $appCreateTime")
    }

    fun onAuthReady() {
        if (authReadyTime != 0L) return
        authReadyTime = System.currentTimeMillis()
        val duration = authReadyTime - appCreateTime
        Log.i("STARTUP_METRICS", "Auth Ready in ${duration}ms")
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
    }
}
