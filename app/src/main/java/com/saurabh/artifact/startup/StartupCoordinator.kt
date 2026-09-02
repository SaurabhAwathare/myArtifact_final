package com.saurabh.artifact.startup

import android.content.Context
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.LogKeys
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

class StartupTimeoutException(message: String) : Exception(message)

/**
 * Defines the technical components that must be ready for the app to function.
 */
enum class StartupComponent {
    CORE,
    AUTH,
    DATABASE,
    SECURITY,
    APP_CHECK,
    RECOVERY,
    FILESYSTEM_DISCOVERY
}

/**
 * Defines the security status of the device/app based on App Check.
 */
enum class SecurityStatus {
    PENDING,
    VERIFIED,
    UNVERIFIED
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
    private val userSessionManager: com.saurabh.artifact.data.local.UserSessionManager,
    private val logoutCoordinator: dagger.Lazy<com.saurabh.artifact.domain.auth.LogoutCoordinator>
) {
    private val scope = CoroutineScope(
        Dispatchers.Main + 
        SupervisorJob() + 
        CoroutineExceptionHandlerUtils.create(DiagnosticCategory.STARTUP, "Orchestrator failure")
    )
    
    private val _stage = MutableStateFlow(StartupStage.ARRIVAL)
    val stage = _stage.asStateFlow()

    private val _securityStatus = MutableStateFlow(SecurityStatus.PENDING)
    val securityStatus = _securityStatus.asStateFlow()

    private val _readyComponents = MutableStateFlow<Set<StartupComponent>>(emptySet())
    
    private val _terminalError = MutableStateFlow<Throwable?>(null)
    val terminalError = _terminalError.asStateFlow()

    private val _preloadResult = MutableStateFlow<PreloadResult?>(null)
    val preloadResult = _preloadResult.asStateFlow()

    companion object {
        private val GLOBAL_STARTUP_TIMEOUT = 20.seconds
    }

    private var isStarted = false
    private var startupJob: Job? = null
    
    private var _isRescueModeActive = false
    val isRescueModeActive: Boolean get() = _isRescueModeActive

    /**
     * Signals that a technical component is ready.
     */
    fun emitReadiness(component: StartupComponent) {
        ArtifactLogger.d(DiagnosticCategory.STARTUP, "READINESS_SIGNALED", mapOf("component" to component.name))
        _readyComponents.update { it + component }
    }

    /**
     * Force completes all readiness signals to unblock the sequence.
     * Use this when a terminal failure occurs.
     */
    fun completeAll() {
        ArtifactLogger.w(DiagnosticCategory.STARTUP, "FORCE_COMPLETING_READINESS")
        _readyComponents.update { it + StartupComponent.entries.toSet() }
    }

    /**
     * Resets the coordinator state to allow for a retry.
     */
    fun reset() {
        ArtifactLogger.i(DiagnosticCategory.STARTUP, "COORDINATOR_RESET")
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
            ArtifactLogger.d(DiagnosticCategory.STARTUP, "READINESS_CONFIRMED", mapOf("component" to component.name))
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ArtifactLogger.e(DiagnosticCategory.STARTUP, "READINESS_TIMEOUT", mapOf("component" to component.name))
            // We proceed anyway to avoid permanent splash screen
        }
    }

    /**
     * Advances the startup sequence through its emotional stages.
     * Uses intentional staggered delays combined with technical readiness signals.
     */
    fun start() {
        if (startupJob?.isActive == true) return
        
        isStarted = true

        val rescueTracker = RescueTracker.getInstance(context)
        _isRescueModeActive = rescueTracker.isRescueModeRequired()

        startupJob = scope.launch {
            val isWarmStart = _stage.value == StartupStage.STABLE
            ArtifactLogger.d(DiagnosticCategory.STARTUP, "SEQUENCE_STARTING", mapOf("stage" to _stage.value.name, "warm" to isWarmStart, "rescue" to _isRescueModeActive))
            StartupTracer.mark("Startup Sequence Started")
            
            if (_isRescueModeActive) {
                initializeRescueMode()
                return@launch
            }

            // Phase 10: Clear stale recording session ID if the service is not active.
            // This ensures that an interrupted recording (due to crash) can be recovered.
            if (com.saurabh.artifact.audio.RecordingService.recordingState.value.status == com.saurabh.artifact.data.local.RecordingStatus.IDLE) {
                ArtifactLogger.i(DiagnosticCategory.STARTUP, "STALE_DRAFT_ID_CLEARED")
                userSessionManager.setActiveDraftId(null)
            }

            // DURABLE RECOVERY: Check for interrupted account deletion or logout
            try {
                val pendingUid = maintenanceRepository.getPendingDeletionUid()
                val isLoggingOut = userSessionManager.isLoggingOut.first()

                if (pendingUid != null || isLoggingOut) {
                    ArtifactLogger.w(DiagnosticCategory.STARTUP, "INTERRUPTED_CLEANUP_DETECTED", mapOf(LogKeys.USER_ID to (pendingUid ?: "unknown"), "isLogout" to isLoggingOut))
                    _stage.value = StartupStage.DELETION_CLEANUP
                    
                    // Execute authoritative local wipe
                    val result = logoutCoordinator.get().performFullCleanup()
                    if (result.status == com.saurabh.artifact.domain.auth.CleanupStatus.COMPLETED) {
                        ArtifactLogger.i(DiagnosticCategory.STARTUP, "RECOVERY_CLEANUP_SUCCESS")
                        maintenanceRepository.setPendingDeletion(null)
                        // Transition back to Arrival to allow normal startup to resume
                        _stage.value = StartupStage.ARRIVAL
                    } else {
                        ArtifactLogger.e(DiagnosticCategory.STARTUP, "RECOVERY_CLEANUP_FAILED")
                        _terminalError.value = Exception("Account deletion recovery failed. Please contact support.")
                        return@launch
                    }
                }
            } catch (e: Exception) {
                ArtifactLogger.e(DiagnosticCategory.STARTUP, "MAINTENANCE_CHECK_FAILED", throwable = e)
            }

            try {
                withTimeout(GLOBAL_STARTUP_TIMEOUT) {
                    val isWarmStart = _stage.value == StartupStage.STABLE
                    
                    if (isWarmStart) {
                        ArtifactLogger.i(DiagnosticCategory.STARTUP, "ACCELERATED_WARM_START")
                        StartupTracer.mark("Warm Start Sequence Initiated")
                        
                        // In a warm start, tech components are usually already ready, 
                        // but we re-verify just in case of transient process states.
                        coroutineScope {
                            launch { awaitAppCheckReadiness() }
                            launch { initializeSecurityProviderSync() }
                        }
                        
                        val result = encryptionManager.preload()
                        if (result is PreloadResult.RecoveryRequired) {
                            emitReadiness(StartupComponent.CORE)
                            return@withTimeout
                        }
                        
                        emitReadiness(StartupComponent.DATABASE)
                        initializeCore()
                        emitReadiness(StartupComponent.CORE)
                        
                        // Compressed "Calm Transition" for Warm Start
                        delay(200.milliseconds)
                        _stage.value = StartupStage.STABLE
                        StartupTracer.mark("Warm Start Complete")
                    } else {
                        // PHASE 1: Mandatory Core Security (Critical for UI and Backend)
                        // Note: initializeAppCheck() must be called in Application.onCreate()
                        coroutineScope {
                            launch { awaitAppCheckReadiness() }
                            launch { initializeSecurityProviderSync() }
                        }

                        // Preload database encryption before signaling CORE
                        val result = encryptionManager.preload()
                        _preloadResult.value = result

                        if (result is PreloadResult.RecoveryRequired) {
                            ArtifactLogger.w(DiagnosticCategory.STARTUP, "DATABASE_RECOVERY_REQUIRED")
                            // UNBLOCK UI: Signal that core technical evaluation is done
                            emitReadiness(StartupComponent.CORE)
                            // Do NOT signal DATABASE readiness yet. UI will handle navigation to Recovery.
                            return@withTimeout 
                        }

                        if (result is PreloadResult.FatalFailure) {
                            throw result.throwable
                        }

                        emitReadiness(StartupComponent.DATABASE)

                        initializeCore() 
                        emitReadiness(StartupComponent.CORE)
                        ArtifactLogger.d(DiagnosticCategory.STARTUP, "CORE_READY")
                        
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

                        // OPTIMIZATION: Removed redundant DATABASE wait here as it's guaranteed by CORE/DATABASE prerequisite.

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
                    }
                }

                // PHASE 4: Late Post-UI (Execution outside global timeout safety net)
                initializePostUI()
                
                val totalDuration = com.saurabh.artifact.util.StartupTracer.getElapsed()
                com.saurabh.artifact.diagnostics.ArtifactLogger.i(
                    com.saurabh.artifact.diagnostics.DiagnosticCategory.STARTUP, 
                    "STARTUP_SUCCESS", 
                    mapOf("totalDuration" to totalDuration)
                )
            } catch (e: TimeoutCancellationException) {
                // RACE GUARD: If we reached STABLE but the timeout triggered just as the block was exiting,
                // we prioritize the success state to prevent false error screens.
                if (_stage.value == StartupStage.STABLE) {
                    ArtifactLogger.i(DiagnosticCategory.STARTUP, "STABLE_REACHED_BEFORE_TIMEOUT")
                    return@launch
                }

                ArtifactLogger.e(
                    DiagnosticCategory.STARTUP, 
                    "STARTUP_TIMEOUT", 
                    mapOf("limit" to GLOBAL_STARTUP_TIMEOUT.toString())
                )
                _terminalError.value = StartupTimeoutException("Startup initialization timed out. Please check your connection and try again.")
                // Force unblock any awaiters to allow error state to propagate
                completeAll()
            } catch (e: Exception) {
                ArtifactLogger.e(
                    DiagnosticCategory.STARTUP, 
                    "STARTUP_FAILED", 
                    throwable = e
                )
                _terminalError.value = e
                // Force unblock any awaiters to allow error state to propagate
                completeAll()
            }
        }
    }

    private fun initializeCore() {
        ArtifactLogger.i(DiagnosticCategory.STARTUP, "ENVIRONMENT_INFO", mapOf("env" to environmentProvider.environment, "projectId" to environmentProvider.firebaseProjectId))
    }

    private suspend fun awaitAppCheckReadiness() {
        val firebaseAppCheck = com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
        ArtifactLogger.d(DiagnosticCategory.STARTUP, "APP_CHECK_START")
        
        var currentAttempt = 0
        val maxAttempts = 3
        var success = false

        while (currentAttempt < maxAttempts && !success) {
            currentAttempt++
            try {
                // Phase A: Non-blocking timeout to ensure startup proceeds
                withTimeout(5.seconds) {
                    firebaseAppCheck.getAppCheckToken(false).await()
                    success = true
                }
            } catch (e: Exception) {
                val category = if (e is kotlinx.coroutines.TimeoutCancellationException) "TIMEOUT" else "ERROR"
                ArtifactLogger.w(DiagnosticCategory.STARTUP, "APP_CHECK_ATTEMPT_FAILED", mapOf("attempt" to currentAttempt, "reason" to category))
                if (currentAttempt < maxAttempts) {
                    delay((currentAttempt * 1000).milliseconds) // Simple backoff
                }
            }
        }

        if (success) {
            ArtifactLogger.i(DiagnosticCategory.STARTUP, "APP_CHECK_VERIFIED")
            _securityStatus.value = SecurityStatus.VERIFIED
        } else {
            ArtifactLogger.w(DiagnosticCategory.STARTUP, "APP_CHECK_UNVERIFIED")
            _securityStatus.value = SecurityStatus.UNVERIFIED
        }
        
        // Signal component readiness (Non-blocking for core flow in Phase A)
        emitReadiness(StartupComponent.APP_CHECK)
    }

    private suspend fun initializeSecurityProviderSync() {
        ArtifactLogger.d(DiagnosticCategory.STARTUP, "SECURITY_PROVIDER_INIT_START")
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(context)

        if (resultCode == ConnectionResult.SUCCESS) {
            return suspendCancellableCoroutine { continuation ->
                ProviderInstaller.installIfNeededAsync(context, object : ProviderInstaller.ProviderInstallListener {
                    override fun onProviderInstalled() {
                        ArtifactLogger.d(DiagnosticCategory.STARTUP, "SECURITY_PROVIDER_READY")
                        emitReadiness(StartupComponent.SECURITY)
                        continuation.resume(Unit)
                    }

                    override fun onProviderInstallFailed(errorCode: Int, recoveryIntent: Intent?) {
                        ArtifactLogger.w(DiagnosticCategory.STARTUP, "SECURITY_PROVIDER_FAILED", mapOf("errorCode" to errorCode))
                        emitReadiness(StartupComponent.SECURITY)
                        continuation.resume(Unit)
                    }
                })
            }
        } else {
            ArtifactLogger.w(DiagnosticCategory.STARTUP, "PLAY_SERVICES_UNAVAILABLE", mapOf("resultCode" to resultCode))
            emitReadiness(StartupComponent.SECURITY)
        }
    }

    private fun initializeBackground() {
        ArtifactLogger.d(DiagnosticCategory.STARTUP, "BACKGROUND_INIT_START")
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
        ArtifactLogger.d(DiagnosticCategory.STARTUP, "POST_UI_INIT_START")
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
        ArtifactLogger.w(DiagnosticCategory.STARTUP, "RESCUE_MODE_ACTIVE")
        
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
            ArtifactLogger.d(DiagnosticCategory.STARTUP, "RESCUE_MODE_READY")
        }
    }
}
