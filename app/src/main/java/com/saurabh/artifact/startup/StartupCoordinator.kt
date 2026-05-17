package com.saurabh.artifact.startup

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.security.ProviderInstaller
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.saurabh.artifact.BuildConfig
import com.saurabh.artifact.audio.RecoveryEngine
import com.saurabh.artifact.util.NotificationHelper
import com.saurabh.artifact.util.StartupTracer
import com.saurabh.artifact.worker.ReminderWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized orchestrator for the Startup Island Architecture.
 * Manages the transition between startup stages to ensure smooth user perception.
 */
@Singleton
class StartupCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recoveryEngine: Lazy<RecoveryEngine>
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val _stage = MutableStateFlow(StartupStage.STABLE)
    val stage = _stage.asStateFlow()

    private var isStarted = false

    /**
     * Advances the startup sequence through its emotional stages.
     * Uses intentional delays to allow for "cognitive settling" and reduced system pressure.
     */
    fun start() {
        if (isStarted) return
        isStarted = true

        scope.launch {
            Log.d("Startup", "Direct Start: STABLE")
            StartupTracer.mark("Startup Sequence Started")
            
            initializeCore()
            initializeSecurityProvider()
            
            withContext(Dispatchers.IO) {
                initializeBackground()
                initializePostUI()
            }
        }
    }

    private fun initializeCore() {
        Log.d("Startup", "Initializing Core Services (Sequenced)")
        NotificationHelper.initNotificationChannels(context)

        // Initialize App Check based on build type
        val appCheck = FirebaseAppCheck.getInstance()
        if (BuildConfig.DEBUG) {
            appCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.d("Startup", "Firebase App Check initialized with Debug provider")
        } else {
            appCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            Log.d("Startup", "Firebase App Check initialized with Play Integrity provider")
        }
    }

    private fun initializeSecurityProvider() {
        try {
            val availability = GoogleApiAvailability.getInstance()
            val resultCode = availability.isGooglePlayServicesAvailable(context)

            if (resultCode == ConnectionResult.SUCCESS) {
                ProviderInstaller.installIfNeededAsync(context, object : ProviderInstaller.ProviderInstallListener {
                    override fun onProviderInstalled() {
                        Log.d("Startup", "Security provider initialized")
                    }

                    override fun onProviderInstallFailed(errorCode: Int, recoveryIntent: android.content.Intent?) {
                        Log.w("Startup", "Security provider failed: $errorCode")
                    }
                })
            }
        } catch (e: Exception) {
            Log.e("Startup", "GMS ProviderInstaller error: ${e.message}")
        }
    }

    private fun initializeBackground() {
        Log.d("Startup", "Initializing Background Services")
        scheduleDailyReminder()
        StartupTracer.mark("Background Services Ready")
    }

    private fun scheduleDailyReminder() {
        val reminderRequest = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(24, TimeUnit.HOURS)
            .addTag("daily_reminder")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "daily_reflection_reminder",
            ExistingPeriodicWorkPolicy.KEEP,
            reminderRequest
        )
    }

    private suspend fun initializePostUI() {
        Log.d("Startup", "Initializing Post-UI Services (Deferred 5s)")
        delay(5000)
        // Run recovery engine only when stable and well after first frame
        recoveryEngine.get().runRecovery()
    }
}
