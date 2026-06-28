package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.worker.IdentitySyncWorker
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Higher-level manager that orchestrates anonymous and authenticated profiles.
 * This is the primary entry point for the UI to get the user's identity marker.
 */
@Singleton
class UserProfileManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionManager: UserSessionManager,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val artifactRepository: ArtifactRepository,
    @com.saurabh.artifact.di.ApplicationScope internal val managerScope: CoroutineScope
) {

    /**
     * Cancel all background tasks. Should only be called during testing or app shutdown.
     */
    internal fun cancelScope() {
        managerScope.cancel()
    }

    init {
        // Initialize anonymous ID if missing
        managerScope.launch {
            sessionManager.ensureAnonymousId()
        }

        // Background Sync: Listen to Firestore updates and push them into the local SSOT
        managerScope.launch {
            authRepository.userData.collectLatest { firestoreUser ->
                if (firestoreUser != null) {
                    sessionManager.syncFromRemote(firestoreUser)
                }
            }
        }
    }

    /**
     * SSOT flow for the entire user profile.
     */
    val userProfile: Flow<com.saurabh.artifact.model.UserProfile> = sessionManager.userProfile

    /**
     * SSOT flow for Avatar Config.
     */
    val activeAvatarConfig: Flow<com.saurabh.artifact.model.AvatarConfig> = sessionManager.userProfile.map { it.avatarConfig }

    /**
     * SSOT flow for the active username.
     */
    val activeUsername: Flow<String> = sessionManager.userProfile.map { it.username }

    /**
     * Updates the user's avatar configuration.
     */
    suspend fun updateAvatarConfig(config: com.saurabh.artifact.model.AvatarConfig): Result<Unit> {
        // 1. Update SSOT immediately
        sessionManager.updateAvatarConfig(config)
        
        val userId = authRepository.currentUserId

        // 2. Optimistic Local Sync
        if (userId.isNotEmpty()) {
            managerScope.launch {
                Log.d("UserProfileManager", "Launching local sync for $userId")
                val currentProfile = sessionManager.userProfile.first()
                Log.d("UserProfileManager", "Got profile for sync: ${currentProfile.username}")
                artifactRepository.updateLocalAuthorSnapshot(
                    userId = userId,
                    snapshot = AuthorSnapshot(
                        anonymousId = currentProfile.anonymousId,
                        name = currentProfile.username,
                        sigil = currentProfile.sigil,
                        avatarSeed = config.seed,
                        avatarColor = currentProfile.avatarColor,
                        avatarConfig = config
                    )
                )
            }
        }

        // 3. Sync to Firestore if authenticated (Eventual Consistency)
        if (userId.isNotEmpty()) {
            val result = userRepository.updateAvatarConfig(userId, config)
            if (result.isSuccess) {
                // For regular updates, we don't strictly track version but still sync
                IdentitySyncWorker.enqueue(context, userId)
            }
            return result
        }
        return Result.success(Unit)
    }

    /**
     * Updates the user's anonymous username.
     */
    suspend fun updateUsername(username: String): Result<Unit> {
        // 1. Update SSOT immediately
        sessionManager.updateUsername(username)
        
        val userId = authRepository.currentUserId
        Log.d("UserProfileManager", "updateUsername: userId='$userId'")

        // 2. Optimistic Local Sync
        if (userId.isNotEmpty()) {
            managerScope.launch {
                Log.d("UserProfileManager", "Launching local sync for $userId")
                val currentProfile = sessionManager.userProfile.first()
                Log.d("UserProfileManager", "Got profile for sync: ${currentProfile.username}")
                artifactRepository.updateLocalAuthorSnapshot(
                    userId = userId,
                    snapshot = AuthorSnapshot(
                        anonymousId = currentProfile.anonymousId,
                        name = username,
                        sigil = currentProfile.sigil,
                        avatarSeed = currentProfile.avatarSeed,
                        avatarColor = currentProfile.avatarColor,
                        avatarConfig = currentProfile.avatarConfig
                    )
                )
            }
        }

        // 3. Sync to Firestore if authenticated (Eventual Consistency)
        if (userId.isNotEmpty()) {
            val result = userRepository.createUsername(userId, username)
            if (result.isSuccess) {
                IdentitySyncWorker.enqueue(context, userId)
            }
            return result
        }
        return Result.success(Unit)
    }

    suspend fun isUsernameAvailable(username: String): Result<Boolean> {
        return userRepository.isUsernameAvailable(username)
    }

    /**
     * Immediately randomizes the user's identity and synchronizes both local and remote state.
     * Acts as the coordinator for the emergency reset flow.
     */
    suspend fun emergencyIdentityReset(userId: String): Result<Unit> {
        Log.i("UserProfileManager", "Starting emergency identity reset orchestration for $userId")
        
        // 1. Trigger Remote Reset (Authority)
        val result = userRepository.emergencyIdentityReset(userId)
        
        if (result.isSuccess) {
            // 2. Synchronize Local Artifact Cache (Optimistic)
            managerScope.launch {
                try {
                    val updatedProfile = sessionManager.userProfile.first()
                    Log.d("UserProfileManager", "Syncing local artifacts for $userId with new identity: ${updatedProfile.username}")
                    
                    artifactRepository.updateLocalAuthorSnapshot(
                        userId = userId,
                        snapshot = AuthorSnapshot(
                            anonymousId = updatedProfile.anonymousId,
                            name = updatedProfile.username,
                            sigil = updatedProfile.sigil,
                            avatarSeed = updatedProfile.avatarSeed,
                            avatarColor = updatedProfile.avatarColor,
                            avatarConfig = updatedProfile.avatarConfig
                        )
                    )
                    Log.i("UserProfileManager", "Local identity synchronization completed for $userId")
                } catch (e: Exception) {
                    Log.e("UserProfileManager", "Local identity synchronization failed for $userId", e)
                }
            }
        } else {
            Log.e("UserProfileManager", "Emergency reset orchestration aborted due to remote failure")
        }
        
        return result
    }
}
