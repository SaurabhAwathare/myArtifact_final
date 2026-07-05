package com.saurabh.artifact.startup

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.security.ProviderInstaller
import android.content.Intent
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.saurabh.artifact.util.CoroutineExceptionHandlerUtils
import com.saurabh.artifact.util.StartupTracer
import com.saurabh.artifact.domain.auth.SessionConstants
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
import kotlin.coroutines.resume
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
    private val workManager: WorkManager,
    private val environmentProvider: com.saurabh.artifact.util.EnvironmentProvider
) {
    private val scope = CoroutineScope(
        Dispatchers.Main + 
        SupervisorJob() + 
        CoroutineExceptionHandlerUtils.create("StartupCoordinator", "Orchestrator failure")
    )
    
    private val _stage = MutableStateFlow(StartupStage.ARRIVAL)
    val stage = _stage.asStateFlow()

    private val _readyComponents = MutableStateFlow<Set<StartupComponent>>(emptySet())
    
    private val _terminalError = MutableStateFlow<Throwable?>(null)
    val terminalError = _terminalError.asStateFlow()

    private var isStarted = false
    private var startupJob: Job? = null
    
    private var _isRescueModeActive = false
    val isRescueModeActive: Boolean get() = _isRescueModeActive

    /**
     * Signals that a technical component is ready.
     */
    fun emitReadiness(component: StartupComponent) {
        Log.d("Startup", "Readiness Signaled: $component")
        _readyComponents.update { it + component }
    }

    /**
     * Force completes all readiness signals to unblock the sequence.
     * Use this when a terminal failure occurs.
     */
    fun completeAll() {
        Log.w("Startup", "Force completing all readiness signals")
        _readyComponents.update { it + StartupComponent.entries.toSet() }
    }

    /**
     * Resets the coordinator state to allow for a retry.
     */
    fun reset() {
        Log.i("Startup", "Resetting coordinator state")
        startupJob?.cancel()
        startupJob = null
        isStarted = false
        _readyComponents.value = emptySet()
        _terminalError.value = null
        _stage.value = StartupStage.ARRIVAL
    }

    /**
     * Suspends until the specified technical component is ready.
     */
    suspend fun awaitComponent(component: StartupComponent) {
        _readyComponents.first { it.contains(component) }
    }

    private suspend fun awaitReadiness(component: StartupComponent) {
        try {
            withTimeout(15.seconds) {
                awaitComponent(component)
            }
            Log.d("Startup", "Readiness Confirmed: $component")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e("Startup", "Readiness Timeout: $component. Proceeding with caution.")
            // We proceed anyway to avoid permanent splash screen
        }
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

        startupJob = scope.launch {
            Log.d("Startup", "Starting Optimized Sequence: ARRIVAL (RescueMode=$_isRescueModeActive)")
            StartupTracer.mark("Startup Sequence Started")
            
            if (_isRescueModeActive) {
                initializeRescueMode()
                return@launch
            }

            try {
                // PHASE 1: Mandatory Core Security (Critical for UI and Backend)
                initializeSecurityProviderSync()
                initializeCore() 
                emitReadiness(StartupComponent.CORE)
                
                // STAGGER 1: Move to Presence after initial frame
                delay(200.milliseconds) 
                _stage.value = StartupStage.PRESENCE
                StartupTracer.mark("Transition: PRESENCE")
                
                // PHASE 2: Deferred & Background Initialization
                launch(Dispatchers.Default) {
                    // BACKGROUND: Schedule tasks away from Main
                    initializeBackground()
                    StartupTracer.mark("Non-critical Services Initialized (Background)")
                }

                // WAIT FOR AUTH before moving to Discovery
                awaitReadiness(StartupComponent.AUTH)

                // STAGGER 2: Discovery (Partial Feed)
                delay(200.milliseconds)
                _stage.value = StartupStage.DISCOVERY
                StartupTracer.mark("Transition: DISCOVERY")

                // WAIT FOR DATABASE before Immersion (where comments/reactions live)
                awaitReadiness(StartupComponent.DATABASE)

                // STAGGER 3: Immersion (Social/Reactions)
                delay(300.milliseconds)
                _stage.value = StartupStage.IMMERSION
                StartupTracer.mark("Transition: IMMERSION")

                // STAGGER 4: Ritual (Media/Player)
                delay(500.milliseconds)
                _stage.value = StartupStage.RITUAL
                StartupTracer.mark("Transition: RITUAL")

                // STAGGER 5: Stable (Full Fidelity)
                delay(500.milliseconds)
                _stage.value = StartupStage.STABLE
                StartupTracer.mark("Transition: STABLE")

                // PHASE 4: Late Post-UI
                initializePostUI()
            } catch (e: Exception) {
                Log.e("Startup", "Terminal failure in startup sequence", e)
                _terminalError.value = e
                // Force unblock any awaiters to allow error state to propagate
                completeAll()
            }
        }
    }

    private fun initializeCore() {
        Log.d("Startup", "Initializing Core Services (App Check)")
        
        Log.i("Startup", "Current Environment: ${environmentProvider.environment}")
        Log.i("Startup", "Firebase Project ID: ${environmentProvider.firebaseProjectId}")

        val appCheck = FirebaseAppCheck.getInstance()
        if (environmentProvider.isDebug) {
            Log.d("Startup", "Installing Debug App Check provider")
            appCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.i("Startup", "App Check: DEBUG MODE active.")
        } else {
            Log.d("Startup", "Installing Play Integrity App Check provider")
            appCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            Log.i("Startup", "App Check: PRODUCTION MODE active.")
        }
    }

    private suspend fun initializeSecurityProviderSync() {
        Log.d("Startup", "Initializing Security Provider (Suspended)")
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(context)

        if (resultCode == ConnectionResult.SUCCESS) {
            return suspendCancellableCoroutine { continuation ->
                ProviderInstaller.installIfNeededAsync(context, object : ProviderInstaller.ProviderInstallListener {
                    override fun onProviderInstalled() {
                        Log.d("Startup", "Security provider initialized")
                        emitReadiness(StartupComponent.SECURITY)
                        continuation.resume(Unit)
                    }

                    override fun onProviderInstallFailed(errorCode: Int, recoveryIntent: Intent?) {
                        Log.w("Startup", "Security provider failed: $errorCode. Proceeding with caution.")
                        emitReadiness(StartupComponent.SECURITY)
                        continuation.resume(Unit)
                    }
                })
            }
        } else {
            Log.w("Startup", "Google Play Services not available: $resultCode")
            emitReadiness(StartupComponent.SECURITY)
        }
    }

    private fun initializeSecurityProvider() {
        // Legacy method, no longer used in mandatory path but kept for safety if referenced elsewhere
        scope.launch(Dispatchers.Default) {
            initializeSecurityProviderSync()
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
            .addTag(SessionConstants.TAG_USER_SESSION_WORK)
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
            .addTag(SessionConstants.TAG_USER_SESSION_WORK)
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
