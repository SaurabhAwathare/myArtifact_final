package com.saurabh.artifact.domain.auth

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import com.saurabh.artifact.audio.MediaCache
import com.saurabh.artifact.audio.MediaPreCacher
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackService
import com.saurabh.artifact.audio.PlaybackSettingsDataStore
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.audio.UploadService
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.SettingsRepository
import com.saurabh.artifact.security.BackupEncryptionManager
import com.saurabh.artifact.util.NotificationHelper
import com.saurabh.artifact.util.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.annotation.OptIn
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class LogoutCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: UserSessionManager,
    private val playbackCoordinator: PlaybackCoordinator,
    private val playbackSettingsDataStore: PlaybackSettingsDataStore,
    private val recordingSessionManager: RecordingSessionManager,
    private val workManager: WorkManager,
    private val database: dagger.Lazy<AppDatabase>,
    private val storageManager: StorageManager,
    private val backupEncryptionManager: BackupEncryptionManager,
    private val onboardingManager: com.saurabh.artifact.util.OnboardingManager,
    private val databaseEncryptionManager: com.saurabh.artifact.security.DatabaseEncryptionManager,
    private val diagnosticLogger: DiagnosticLogger
) {

    // Dispatchers can be overridden for testing
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    var mainDispatcher: CoroutineDispatcher = Dispatchers.Main

    private val cleanupMutex = Mutex()

    /**
     * Executes the comprehensive logout sequence.
     * Hardens the application by clearing all user-specific state and stopping active media.
     * Protected by a Mutex to prevent race conditions during rapid taps.
     */
    suspend fun executeLogout(): Result<CleanupResult> {
        return try {
            val cleanupResult = performFullCleanup()
            
            // Phase E: Remote Session Termination
            // Only proceed if a fresh cleanup was completed or if already in progress (resilient)
            if (cleanupResult.status != CleanupStatus.ALREADY_IN_PROGRESS) {
                try {
                    authRepository.signOut()
                    diagnosticLogger.info(DiagnosticCategory.AUTH, "LOGOUT_FIREBASE_SUCCESS")
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_FIREBASE_FAILED", throwable = e)
                }
            }
            
            Result.success(cleanupResult)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_FATAL_ERROR", throwable = e)
            Result.failure(e)
        }
    }

    /**
     * Clears local user data and stops active media.
     * Executed in deterministic phases: Phase A (Stop), Phase B (Clear State), Phase C (Database), Phase D (Finalize).
     * Guaranteed to execute only once via Mutex protection.
     */
    suspend fun performFullCleanup(): CleanupResult {
        // Concurrency Control: Prevent duplicate cleanup execution
        if (cleanupMutex.isLocked) {
            diagnosticLogger.info(
                DiagnosticCategory.AUTH,
                "LOGOUT_CLEANUP_SKIPPED",
                mapOf("reason" to "Already in progress")
            )
            return CleanupResult(status = CleanupStatus.ALREADY_IN_PROGRESS)
        }

        return cleanupMutex.withLock {
            withContext(ioDispatcher) {
                diagnosticLogger.info(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_STARTED")

                var recordingSuccess = true
                var playbackSuccess = true
                var uploadsSuccess = true
                var workersSuccess = true
                var notificationsSuccess = true
                var roomSuccess = true
                var sessionDSSuccess = true
                var settingsDSSuccess = true
                var mediaCacheSuccess = true

                // PHASE A: Stop Active Work (Prevent new IO/writes)
                diagnosticLogger.debug(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_PHASE_A")
                
                // 1. Stop active recording
                try {
                    withContext(mainDispatcher) {
                        if (recordingSessionManager.isRecordingActive()) {
                            diagnosticLogger.info(DiagnosticCategory.AUTH, "LOGOUT_STOP_RECORDING")
                            recordingSessionManager.cancelSession()
                        }
                    }
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_STOP_RECORDING_FAILED", throwable = e)
                    recordingSuccess = false
                }

                // 2. Release playback and pre-cache resources (Deterministic)
                try {
                    withContext(mainDispatcher) {
                        playbackCoordinator.release()
                        MediaPreCacher.cancelAll()
                        context.stopService(Intent(context, PlaybackService::class.java))
                    }
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_STOP_PLAYBACK_FAILED", throwable = e)
                    playbackSuccess = false
                }

                // 3. Stop background upload service
                try {
                    context.stopService(Intent(context, UploadService::class.java))
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_STOP_UPLOAD_FAILED", throwable = e)
                    uploadsSuccess = false
                }

                // 4. Cancel user-scoped background workers
                try {
                    workManager.cancelAllWorkByTag(SessionConstants.TAG_USER_SESSION_WORK).result.await()
                    // Small delay to ensure WorkManager has yielded DB locks
                    delay(500)
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_STOP_WORKERS_FAILED", throwable = e)
                    workersSuccess = false
                }

                // 5. Cancel notifications
                try {
                    NotificationHelper.cancelAllNotifications(context)
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_STOP_NOTIFICATIONS_FAILED", throwable = e)
                    notificationsSuccess = false
                }

                // PHASE B: Local Data Destruction (Database & Files)
                // We perform this before clearing DataStores so owningUid remains as a taint flag if failure occurs.
                diagnosticLogger.debug(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_PHASE_B")
                
                // 6. Database Cleanup
                try {
                    database.get().clearAllTables()
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_DB_FAILED", throwable = e)
                    
                    // FALLBACK: If clearAllTables fails (e.g. locks), try destructive delete
                    try {
                        diagnosticLogger.warn(DiagnosticCategory.AUTH, "LOGOUT_DB_FALLBACK_TRIGGERED")
                        database.get().close()
                        val deleted = context.deleteDatabase("artifact_db")
                        if (deleted) {
                            diagnosticLogger.info(DiagnosticCategory.AUTH, "LOGOUT_DB_FALLBACK_SUCCESS")
                            roomSuccess = true
                        } else {
                            diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_DB_FALLBACK_RETURNED_FALSE")
                            roomSuccess = false
                        }
                    } catch (fallbackError: Exception) {
                        diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_DB_FALLBACK_FAILED", throwable = fallbackError)
                        roomSuccess = false
                    }
                }

                // 7. User File Cleanup
                try {
                    storageManager.clearUserStorage()
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_STORAGE_FAILED", throwable = e)
                }

                // PHASE C: Clear Local State (DataStores)
                // Critical boundary: only clear these if destructive cleanup was successful
                diagnosticLogger.debug(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_PHASE_C")
                
                if (roomSuccess) {
                    // 8. Clear Session DataStore (Contains owningUid - the taint marker)
                    try {
                        sessionManager.clear()
                    } catch (e: Exception) {
                        diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_SESSION_FAILED", throwable = e)
                        sessionDSSuccess = false
                    }

                    // 9. Clear Settings DataStore
                    try {
                        settingsRepository.signOut()
                    } catch (e: Exception) {
                        diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_SETTINGS_FAILED", throwable = e)
                        settingsDSSuccess = false
                    }

                    // 10. Clear Playback State DataStore
                    try {
                        playbackSettingsDataStore.clear()
                    } catch (e: Exception) {
                        diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_PLAYBACK_DS_FAILED", throwable = e)
                    }

                    // 10.5 Clear Backup Security State
                    try {
                        backupEncryptionManager.clear()
                    } catch (e: Exception) {
                        diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_BACKUP_FAILED", throwable = e)
                    }

                    // 10.6 Clear Onboarding State
                    try {
                        onboardingManager.clear()
                    } catch (e: Exception) {
                        diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_ONBOARDING_FAILED", throwable = e)
                    }

                    // 10.7 Clear Database Encryption State
                    try {
                        databaseEncryptionManager.clear()
                    } catch (e: Exception) {
                        diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_DB_ENCRYPTION_FAILED", throwable = e)
                    }
                } else {
                    diagnosticLogger.warn(DiagnosticCategory.AUTH, "LOGOUT_SKIP_DATASTORE_CLEAR", mapOf("reason" to "DB cleanup failed"))
                    sessionDSSuccess = false
                }

                // PHASE D: Finalize
                diagnosticLogger.debug(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_PHASE_D")
                
                // 11. Release Media Cache handles
                try {
                    MediaCache.release()
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_RELEASE_CACHE_FAILED", throwable = e)
                    mediaCacheSuccess = false
                }

                CleanupResult(
                    status = CleanupStatus.COMPLETED,
                    recording = recordingSuccess,
                    playback = playbackSuccess,
                    uploads = uploadsSuccess,
                    workers = workersSuccess,
                    notifications = notificationsSuccess,
                    room = roomSuccess,
                    sessionDataStore = sessionDSSuccess,
                    settingsDataStore = settingsDSSuccess,
                    mediaCache = mediaCacheSuccess
                ).also {
                    diagnosticLogger.info(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_COMPLETED")
                    diagnosticLogger.debug(DiagnosticCategory.AUTH, "SESSION_CLEANUP_COMPLETED")
                }
            }
        }
    }
}
