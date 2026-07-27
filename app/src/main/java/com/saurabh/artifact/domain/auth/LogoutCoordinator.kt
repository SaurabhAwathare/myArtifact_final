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
    private val database: AppDatabase,
    private val storageManager: StorageManager,
    private val backupEncryptionManager: BackupEncryptionManager,
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

                // PHASE B: Clear Local State (DataStores)
                diagnosticLogger.debug(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_PHASE_B")
                
                // 6. Clear Session DataStore
                try {
                    sessionManager.clear()
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_SESSION_FAILED", throwable = e)
                    sessionDSSuccess = false
                }

                // 7. Clear Settings DataStore
                try {
                    settingsRepository.signOut()
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_SETTINGS_FAILED", throwable = e)
                    settingsDSSuccess = false
                }

                // 8. Clear Playback State DataStore
                try {
                    playbackSettingsDataStore.clear()
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_PLAYBACK_DS_FAILED", throwable = e)
                    settingsDSSuccess = settingsDSSuccess && false 
                }

                // 8.5 Invalidate Backup Encryption Cache
                try {
                    backupEncryptionManager.invalidateCache()
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_BACKUP_CACHE_FAILED", throwable = e)
                }

                // PHASE C: Database Cleanup
                diagnosticLogger.debug(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_PHASE_C")
                try {
                    database.clearAllTables()
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_DB_FAILED", throwable = e)
                    roomSuccess = false
                }

                // PHASE C.5: User File Cleanup
                diagnosticLogger.debug(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_PHASE_C_5")
                try {
                    storageManager.clearUserStorage()
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_STORAGE_FAILED", throwable = e)
                }

                // PHASE D: Finalize
                diagnosticLogger.debug(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_PHASE_D")
                
                // 9. Release Media Cache handles
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
                }
            }
        }
    }
}
