package com.saurabh.artifact.domain.auth

import android.content.Context
import android.util.Log
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackSettingsDataStore
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    private val database: AppDatabase
) {

    /**
     * Executes the comprehensive logout sequence.
     * Hardens the application by clearing all user-specific state and stopping active media.
     */
    suspend fun executeLogout(): Result<Unit> = withContext(Dispatchers.IO) {
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

    /**
     * Clears all local user data and stops active media without signing out of Firebase.
     * Useful for scenarios like account deletion where Auth termination is handled separately.
     */
    suspend fun performFullCleanup() = withContext(Dispatchers.IO) {
        // 1. Stop active playback immediately to prevent audio leak
        withContext(Dispatchers.Main) {
            playbackCoordinator.stop()
        }
        Log.d("LogoutHardening", "1. Active playback stopped.")

        // 2. Clear Playback State (Queue, Last Artifact, Speed, etc.)
        playbackSettingsDataStore.clear()
        Log.d("LogoutHardening", "2. Playback settings cleared.")

        // 3. Clear Room Database (Drafts, Engagements, Pending Actions)
        database.clearAllTables()
        Log.d("LogoutHardening", "3. Room database tables cleared.")

        // 4. Clear Local Session State (Anonymous ID, Username, Sigil)
        sessionManager.clear()
        Log.d("LogoutHardening", "4. Session DataStore cleared.")

        // 5. Clear User Settings (Preferences, Stealth Mode, Biometrics)
        settingsRepository.signOut()
        Log.d("LogoutHardening", "5. Local settings DataStore cleared.")
    }
}
