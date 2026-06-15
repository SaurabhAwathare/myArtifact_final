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
import com.saurabh.artifact.util.CoroutineExceptionHandlerUtils
import com.saurabh.artifact.util.StartupTracer
import com.saurabh.artifact.worker.ReminderWorker
import com.saurabh.artifact.worker.RecoveryWorker
import com.saurabh.artifact.worker.CleanupOrphanFilesWorker
import com.saurabh.artifact.worker.PublishingRecoveryWorker
import com.saurabh.artifact.util.RescueTracker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Defines the technical components that must be ready for the app to function.
 */
enum class StartupComponent {
    CORE,
    AUTH,
    DATABASE,
    SECURITY,
    RECOVERY
}

/**
 * Centralized orchestrator for the Startup Island Architecture.
 * Manages the transition between startup stages to ensure smooth user perception.
 */
@Singleton
class StartupCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workManager: WorkManager
) {
    private val scope = CoroutineScope(
        Dispatchers.Main + 
        SupervisorJob() + 
        CoroutineExceptionHandlerUtils.create("StartupCoordinator", "Orchestrator failure")
    )
    
    private val _stage = MutableStateFlow(StartupStage.ARRIVAL)
    val stage = _stage.asStateFlow()

    private val _readyComponents = MutableStateFlow<Set<StartupComponent>>(emptySet())
    private var isStarted = false
    
    private var _isRescueModeActive = false
    val isRescueModeActive: Boolean get() = _isRescueModeActive

    /**
     * Signals that a technical component is ready.
     */
    fun emitReadiness(component: StartupComponent) {
        Log.d("Startup", "Readiness Signaled: $component")
        _readyComponents.update { it + component }
    }

    private suspend fun awaitReadiness(component: StartupComponent) {
        _readyComponents.first { it.contains(component) }
        Log.d("Startup", "Readiness Confirmed: $component")
    }

    /**
     * Advances the startup sequence through its emotional stages.
     * Uses intentional staggered delays combined with technical readiness signals.
     */
    fun start() {
        if (isStarted) return
        isStarted = true

        val rescueTracker = RescueTracker.getInstance(context)
        _isRescueModeActive = rescueTracker.isRescueModeRequired()

        scope.launch {
            Log.d("Startup", "Starting Optimized Sequence: ARRIVAL (RescueMode=$_isRescueModeActive)")
            StartupTracer.mark("Startup Sequence Started")
            
            if (_isRescueModeActive) {
                initializeRescueMode()
                return@launch
            }

            // PHASE 1: Immediate Core (Critical for UI availability)
            initializeCore()
            emitReadiness(StartupComponent.CORE)
            
            // STAGGER 1: Move to Presence after initial frame
            delay(200.milliseconds) // Reduced from 500ms
            _stage.value = StartupStage.PRESENCE
            StartupTracer.mark("Transition: PRESENCE")
            
            // PHASE 2: Deferred & Background Initialization
            launch(Dispatchers.Default) {
                // SECURITY: Wait for core and then install
                awaitReadiness(StartupComponent.CORE)
                delay(200.milliseconds) // Reduced from 400ms
                initializeSecurityProvider()
                
                // BACKGROUND: Schedule tasks away from Main
                initializeBackground()
                
                StartupTracer.mark("Non-critical Services Initialized (Background)")
            }

            // WAIT FOR AUTH before moving to Discovery
            // This ensures we don't show the feed until we know who the user is
            awaitReadiness(StartupComponent.AUTH)

            // STAGGER 2: Discovery (Partial Feed)
            delay(200.milliseconds) // Reduced from 500ms
            _stage.value = StartupStage.DISCOVERY
            StartupTracer.mark("Transition: DISCOVERY")

            // WAIT FOR DATABASE before Immersion (where comments/reactions live)
            awaitReadiness(StartupComponent.DATABASE)

            // STAGGER 3: Immersion (Social/Reactions)
            delay(300.milliseconds) // Reduced from 500ms
            _stage.value = StartupStage.IMMERSION
            StartupTracer.mark("Transition: IMMERSION")

            // STAGGER 4: Ritual (Media/Player)
            delay(500.milliseconds) // Reduced from 1000ms
            _stage.value = StartupStage.RITUAL
            StartupTracer.mark("Transition: RITUAL")

            // STAGGER 5: Stable (Full Fidelity)
            delay(500.milliseconds) // Reduced from 1s
            _stage.value = StartupStage.STABLE
            StartupTracer.mark("Transition: STABLE")

            // PHASE 4: Late Post-UI
            initializePostUI()
        }
    }

    private fun initializeCore() {
        Log.d("Startup", "Initializing Core Services (Sequenced)")

        // DIAGNOSTIC: Disabling App Check to isolate SecurityException
        Log.d("Startup", "DIAGNOSTIC: Firebase App Check initialization SKIPPED")
        /*
        try {
            val appCheck = FirebaseAppCheck.getInstance()
            if (BuildConfig.DEBUG) {
                Log.d("Startup", "Installing Debug App Check provider")
                appCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                // Enhanced logging for debug token discovery
                Log.i("Startup", "App Check: DEBUG MODE. If you see 'Too many attempts', ensure your Debug Token (printed in logcat earlier) is registered in the Firebase Console.")
            } else {
                Log.d("Startup", "Installing Play Integrity App Check provider")
                appCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                )
            }
        } catch (e: Exception) {
            Log.e("Startup", "Critical: App Check initialization failed", e)
        }
        */
    }

    private fun initializeSecurityProvider() {
        try {
            val availability = GoogleApiAvailability.getInstance()
            val resultCode = availability.isGooglePlayServicesAvailable(context)

            if (resultCode == ConnectionResult.SUCCESS) {
                // Security provider must be installed on UI thread if using certain GMS features, 
                // but ProviderInstaller.installIfNeededAsync is designed to be called from anywhere.
                // However, the error log "Must be called on the UI thread" suggests an internal requirement here.
                scope.launch(Dispatchers.Main) {
                    ProviderInstaller.installIfNeededAsync(context, object : ProviderInstaller.ProviderInstallListener {
                        override fun onProviderInstalled() {
                            Log.d("Startup", "Security provider initialized")
                            emitReadiness(StartupComponent.SECURITY)
                        }

                        override fun onProviderInstallFailed(errorCode: Int, recoveryIntent: android.content.Intent?) {
                            Log.w("Startup", "Security provider failed: $errorCode")
                            // We still emit readiness to unblock the sequence, but with a warning
                            emitReadiness(StartupComponent.SECURITY)
                        }
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("Startup", "GMS ProviderInstaller error: ${e.message}")
        }
    }

    private fun initializeBackground() {
        Log.d("Startup", "Initializing Background Services")
        scheduleDailyReminder()
        scheduleOrphanCleanup()
        schedulePublishingRecovery()
        StartupTracer.mark("Background Services Ready")
    }

    private fun scheduleDailyReminder() {
        val reminderRequest = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(24, TimeUnit.HOURS)
            .addTag("daily_reminder")
            .build()

        workManager.enqueueUniquePeriodicWork(
            "daily_reflection_reminder",
            ExistingPeriodicWorkPolicy.KEEP,
            reminderRequest
        )
    }

    private fun scheduleOrphanCleanup() {
        // Run orphan cleanup every 24 hours
        val cleanupRequest = PeriodicWorkRequestBuilder<CleanupOrphanFilesWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(30, TimeUnit.SECONDS) // Run soon after startup
            .addTag("orphan_cleanup")
            .build()

        workManager.enqueueUniquePeriodicWork(
            "orphan_media_cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupRequest
        )
    }

    private fun schedulePublishingRecovery() {
        // Run publishing recovery every 1 hour
        val recoveryRequest = PeriodicWorkRequestBuilder<PublishingRecoveryWorker>(1, TimeUnit.HOURS)
            .setInitialDelay(1, TimeUnit.MINUTES)
            .addTag("publishing_recovery")
            .build()

        workManager.enqueueUniquePeriodicWork(
            "publishing_recovery_job",
            ExistingPeriodicWorkPolicy.KEEP,
            recoveryRequest
        )
    }

    private suspend fun initializePostUI() {
        Log.d("Startup", "Initializing Post-UI Services (Deferred 5s)")
        delay(5.seconds)
        
        // Trigger automated recovery worker
        val recoveryRequest = OneTimeWorkRequestBuilder<RecoveryWorker>()
            .setInitialDelay(5, TimeUnit.SECONDS)
            .addTag("startup_recovery")
            .build()
            
        workManager.enqueueUniqueWork(
            "startup_recovery_job",
            ExistingWorkPolicy.KEEP,
            recoveryRequest
        )
    }

    private fun initializeRescueMode() {
        Log.w("Startup", "INITIALIZING IN RESCUE MODE")
        
        // Skip background tasks, skip most stagings
        // Only initialize absolute minimum for the Rescue UI
        scope.launch {
            initializeCore()
            emitReadiness(StartupComponent.CORE)
            
            // Advance to a state where UI can bind
            delay(200.milliseconds)
            _stage.value = StartupStage.PRESENCE
            
            // Signal that we are ready for the Rescue Screen
            emitReadiness(StartupComponent.RECOVERY)
            Log.d("Startup", "Rescue Mode Readiness Emitted")
        }
    }
}
