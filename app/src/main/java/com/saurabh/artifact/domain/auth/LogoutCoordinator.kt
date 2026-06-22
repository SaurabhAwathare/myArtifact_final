package com.saurabh.artifact.domain.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackSettingsDataStore
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.audio.UploadService
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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
    private val database: AppDatabase
) {

    private val logoutMutex = Mutex()

    /**
     * Executes the comprehensive logout sequence.
     * Hardens the application by clearing all user-specific state and stopping active media.
     * Protected by a Mutex to prevent race conditions during rapid taps.
     */
    suspend fun executeLogout(): Result<Unit> = logoutMutex.withLock {
        withContext(Dispatchers.IO) {
            Log.i("LogoutHardening", "Initiating comprehensive logout sequence...")
            
            return@withContext try {
                performFullCleanup()
                
                // 6. Perform Firebase SignOut (Remote Session Termination)
                authRepository.signOut()
                Log.d("LogoutHardening", "6. Firebase sign-out complete.")
                
                Log.i("LogoutHardening", "Logout sequence completed successfully.")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e("LogoutHardening", "Logout sequence failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Clears all local user data and stops active media without signing out of Firebase.
     * Useful for scenarios like account deletion where Auth termination is handled separately.
     */
    suspend fun performFullCleanup() = withContext(Dispatchers.IO) {
        // 1. Stop active recording immediately (Privacy Critical)
        withContext(Dispatchers.Main) {
            if (recordingSessionManager.isRecordingActive()) {
                Log.d("LogoutHardening", "Active recording detected. Terminating session.")
                recordingSessionManager.cancelSession()
            }
        }
        Log.d("LogoutHardening", "1. Recording session terminated.")

        // 2. Stop active playback immediately to prevent audio leak
        withContext(Dispatchers.Main) {
            playbackCoordinator.stop()
        }
        Log.d("LogoutHardening", "2. Active playback stopped.")

        // 3. Stop background upload service
        context.stopService(Intent(context, UploadService::class.java))
        Log.d("LogoutHardening", "3. Upload service stopped.")

        // 4. Cancel all user-scoped background workers
        workManager.cancelAllWorkByTag(SessionConstants.TAG_USER_SESSION_WORK)
        Log.d("LogoutHardening", "4. User-session background workers cancelled.")

        // 5. Clear Playback State (Queue, Last Artifact, Speed, etc.)
        playbackSettingsDataStore.clear()
        Log.d("LogoutHardening", "5. Playback settings cleared.")

        // 6. Clear Room Database (Drafts, Engagements, Pending Actions)
        // This also clears temporary draft metadata
        database.clearAllTables()
        Log.d("LogoutHardening", "6. Room database tables cleared.")

        // 7. Clear Local Session State (Anonymous ID, Username, Sigil)
        sessionManager.clear()
        Log.d("LogoutHardening", "7. Session DataStore cleared.")

        // 8. Clear User Settings (Preferences, Stealth Mode, Biometrics)
        settingsRepository.signOut()
        Log.d("LogoutHardening", "8. Local settings DataStore cleared.")
    }
}
