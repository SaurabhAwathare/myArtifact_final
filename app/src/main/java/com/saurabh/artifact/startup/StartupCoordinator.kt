package com.saurabh.artifact.startup

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.security.ProviderInstaller
import android.content.Intent
import com.saurabh.artifact.security.PreloadResult
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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
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
    private val encryptionManager: com.saurabh.artifact.security.DatabaseEncryptionManager,
    private val environmentProvider: com.saurabh.artifact.util.EnvironmentProvider,
    private val cleanupManager: dagger.Lazy<com.saurabh.artifact.audio.ArtifactCleanupManager>,
    private val maintenanceRepository: com.saurabh.artifact.repository.MaintenanceRepository,
    private val logoutCoordinator: dagger.Lazy<com.saurabh.artifact.domain.auth.LogoutCoordinator>
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

    private val _preloadResult = MutableStateFlow<PreloadResult?>(null)
    val preloadResult = _preloadResult.asStateFlow()

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
        _preloadResult.value = null
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

            // DURABLE RECOVERY: Check for interrupted account deletion
            try {
                val pendingUid = maintenanceRepository.getPendingDeletionUid()
                if (pendingUid != null) {
                    Log.w("Startup", "Pending account deletion detected for UID: $pendingUid. LOCKING STARTUP.")
                    _stage.value = StartupStage.DELETION_CLEANUP
                    
                    // Execute authoritative local wipe
                    val result = logoutCoordinator.get().performFullCleanup()
                    if (result.status == com.saurabh.artifact.domain.auth.CleanupStatus.COMPLETED) {
                        Log.i("Startup", "Recovery cleanup completed successfully. Releasing lock.")
                        maintenanceRepository.setPendingDeletion(null)
                        // Transition back to Arrival to allow normal startup to resume
                        _stage.value = StartupStage.ARRIVAL
                    } else {
                        Log.e("Startup", "Recovery cleanup failed. Startup remains locked.")
                        _terminalError.value = Exception("Account deletion recovery failed. Please contact support.")
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Log.e("Startup", "Failed to check maintenance state", e)
            }

            try {
                // PHASE 1: Mandatory Core Security (Critical for UI and Backend)
                // Note: initializeAppCheck() must be called in Application.onCreate()
                awaitAppCheckReadiness()
                initializeSecurityProviderSync()

                // Preload database encryption before signaling CORE
                val result = encryptionManager.preload()
                _preloadResult.value = result

                if (result is PreloadResult.RecoveryRequired) {
                    Log.w("Startup", "DATABASE RECOVERY REQUIRED. HOLDING STARTUP.")
                    // Do NOT signal DATABASE readiness yet. UI will handle navigation to Recovery.
                    return@launch 
                }

                if (result is PreloadResult.FatalFailure) {
                    throw result.throwable
                }

                emitReadiness(StartupComponent.DATABASE)

                initializeCore() 
                emitReadiness(StartupComponent.CORE)
                Log.d("RACE_CHECK", "CORE_READY")
                
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

                // WAIT FOR DATABASE before Immersion (where social interactions live)
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
                
                val totalDuration = com.saurabh.artifact.util.StartupTracer.getElapsed()
                com.saurabh.artifact.diagnostics.ArtifactLogger.i(
                    com.saurabh.artifact.diagnostics.DiagnosticCategory.STARTUP, 
                    "STARTUP_SUCCESS", 
                    mapOf("totalDuration" to totalDuration)
                )
            } catch (e: Exception) {
                com.saurabh.artifact.diagnostics.ArtifactLogger.e(
                    com.saurabh.artifact.diagnostics.DiagnosticCategory.STARTUP, 
                    "STARTUP_FAILED", 
                    throwable = e
                )
                Log.e("Startup", "Terminal failure in startup sequence", e)
                _terminalError.value = e
                // Force unblock any awaiters to allow error state to propagate
                completeAll()
            }
        }
    }

    private fun initializeCore() {
        Log.i("Startup", "Current Environment: ${environmentProvider.environment}")
        Log.i("Startup", "Firebase Project ID: ${environmentProvider.firebaseProjectId}")
    }

    private suspend fun awaitAppCheckReadiness() {
        val firebaseAppCheck = com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
        Log.d("RACE_CHECK", "APP_CHECK_TOKEN_REQUEST_START")
        try {
            val result = firebaseAppCheck.getAppCheckToken(false).await()
            val token = result.token
            Log.d("RACE_CHECK", "APP_CHECK_TOKEN_RECEIVED: ${token.take(10)}... (length=${token.length})")
        } catch (e: Exception) {
            Log.e("RACE_CHECK", "App Check Token initial exchange failed", e)
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

    private fun initializeBackground() {
        Log.d("Startup", "Initializing Background Services")
        scheduleDailyReminder()
        scheduleOrphanCleanup()
        schedulePublishingRecovery()
        
        // Phase 2: Resume any interrupted artifact cleanups
        cleanupManager.get().resumeUnfinishedCleanups()
        
        // STABILIZATION: Cleanup any stale temporary decrypted files from cache
        cleanupManager.get().cleanStaleTempFiles(context.cacheDir)
        
        StartupTracer.mark("Background Services Ready")
    }

    private fun scheduleDailyReminder() {
        val reminderRequest = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(24, TimeUnit.HOURS)
            .addTag("daily_reminder")
            .addTag(SessionConstants.TAG_USER_SESSION_WORK)
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
            .addTag(SessionConstants.TAG_USER_SESSION_WORK)
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
