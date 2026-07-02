package com.saurabh.artifact.domain.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import com.saurabh.artifact.audio.MediaCache
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackSettingsDataStore
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.audio.UploadService
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.SettingsRepository
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
import javax.inject.Inject
import javax.inject.Singleton

@UnstableApi
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
                    Log.d("LogoutCoordinator", "Firebase sign-out complete.")
                } catch (e: Exception) {
                    Log.e("LogoutCoordinator", "Firebase sign-out failed", e)
                }
            }
            
            Result.success(cleanupResult)
        } catch (e: Exception) {
            Log.e("LogoutCoordinator", "Logout sequence encountered a fatal error.")
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
            Log.i("LogoutCoordinator", "Cleanup already in progress. Skipping redundant request.")
            return CleanupResult(status = CleanupStatus.ALREADY_IN_PROGRESS)
        }

        return cleanupMutex.withLock {
            withContext(ioDispatcher) {
                Log.i("LogoutCoordinator", "Initiating deterministic cleanup pipeline...")

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
                Log.d("LogoutCoordinator", "[Phase A] Stopping active work...")
                
                // 1. Stop active recording
                try {
                    withContext(mainDispatcher) {
                        if (recordingSessionManager.isRecordingActive()) {
                            Log.d("LogoutCoordinator", "Terminating active recording session.")
                            recordingSessionManager.cancelSession()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LogoutCoordinator", "Failed to stop recording", e)
                    recordingSuccess = false
                }

                // 2. Stop active playback
                try {
                    withContext(mainDispatcher) {
                        playbackCoordinator.stop()
                    }
                } catch (e: Exception) {
                    Log.e("LogoutCoordinator", "Failed to stop playback", e)
                    playbackSuccess = false
                }

                // 3. Stop background upload service
                try {
                    context.stopService(Intent(context, UploadService::class.java))
                } catch (e: Exception) {
                    Log.e("LogoutCoordinator", "Failed to stop upload service", e)
                    uploadsSuccess = false
                }

                // 4. Cancel user-scoped background workers
                try {
                    workManager.cancelAllWorkByTag(SessionConstants.TAG_USER_SESSION_WORK).result.await()
                } catch (e: Exception) {
                    Log.e("LogoutCoordinator", "Failed to cancel background workers", e)
                    workersSuccess = false
                }

                // 5. Cancel notifications
                try {
                    NotificationHelper.cancelAllNotifications(context)
                } catch (e: Exception) {
                    Log.e("LogoutCoordinator", "Failed to cancel notifications", e)
                    notificationsSuccess = false
                }

                // Grace period to allow services to release locks
                delay(200)

                // PHASE B: Clear Local State (DataStores)
                Log.d("LogoutCoordinator", "[Phase B] Clearing local DataStores...")
                
                // 6. Clear Session DataStore
                try {
                    sessionManager.clear()
                } catch (e: Exception) {
                    Log.e("LogoutCoordinator", "Failed to clear Session DataStore", e)
                    sessionDSSuccess = false
                }

                // 7. Clear Settings DataStore
                try {
                    settingsRepository.signOut()
                } catch (e: Exception) {
                    Log.e("LogoutCoordinator", "Failed to clear Settings DataStore", e)
                    settingsDSSuccess = false
                }

                // 8. Clear Playback State DataStore
                try {
                    playbackSettingsDataStore.clear()
                } catch (e: Exception) {
                    Log.e("LogoutCoordinator", "Failed to clear Playback DataStore", e)
                    settingsDSSuccess = settingsDSSuccess && false 
                }

                // PHASE C: Database Cleanup
                Log.d("LogoutCoordinator", "[Phase C] Clearing Room database...")
                try {
                    database.clearAllTables()
                } catch (e: Exception) {
                    Log.e("LogoutCoordinator", "Failed to clear Room database", e)
                    roomSuccess = false
                }

                // PHASE C.5: User File Cleanup
                Log.d("LogoutCoordinator", "[Phase C.5] Clearing user file storage...")
                try {
                    storageManager.clearUserStorage()
                    Log.i("LogoutCoordinator", "User storage cleanup completed.")
                } catch (e: Exception) {
                    Log.e("LogoutCoordinator", "Failed to clear user storage", e)
                }

                // PHASE D: Finalize
                Log.d("LogoutCoordinator", "[Phase D] Finalizing cleanup...")
                
                // 9. Release Media Cache handles
                try {
                    MediaCache.release()
                } catch (e: Exception) {
                    Log.e("LogoutCoordinator", "Failed to release MediaCache", e)
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
                    Log.i("LogoutCoordinator", "Cleanup complete.")
                }
            }
        }
    }
}
