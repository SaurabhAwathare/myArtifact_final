package com.saurabh.artifact.startup

import android.content.Context
import androidx.startup.Initializer
import android.util.Log
import com.google.firebase.FirebaseApp
import com.saurabh.artifact.util.NotificationHelper
import com.saurabh.artifact.util.StartupTracer

/**
 * foundational initializer that sets up core tracing and system components
 * before the Application class is even created.
 */
@Suppress("unused")
class DependencyGraphInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Start tracing at the absolute earliest point in the process lifecycle
        StartupTracer.mark("Process Started (App Startup)")

        // Initialize metrics baseline
        StartupMetrics.onAppCreate()

        // Load native library as early as possible
        try {
            System.loadLibrary("sqlcipher")
            StartupTracer.mark("SQLCipher Loaded")
        } catch (e: Exception) {
            Log.e("Startup", "Failed to load sqlcipher", e)
        }

        // Check if Firebase is already initialized
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
            StartupTracer.mark("Firebase Initialized (Explicit)")
        }

        // Ensure system channels are ready before any potential background work triggers them
        NotificationHelper.initNotificationChannels(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}
