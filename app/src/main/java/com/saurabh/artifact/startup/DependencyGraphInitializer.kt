package com.saurabh.artifact.startup

import android.content.Context
import androidx.startup.Initializer
import com.saurabh.artifact.util.NotificationHelper
import com.saurabh.artifact.util.StartupTracer

/**
 * foundational initializer that sets up core tracing and system components
 * before the Application class is even created.
 */
class DependencyGraphInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Start tracing at the absolute earliest point in the process lifecycle
        StartupTracer.mark("Process Started (App Startup)")

        // Initialize metrics baseline
        StartupMetrics.onAppCreate()

        // Ensure system channels are ready before any potential background work triggers them
        NotificationHelper.initNotificationChannels(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}
