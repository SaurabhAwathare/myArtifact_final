package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.UserSessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Higher-level manager that orchestrates anonymous and authenticated profiles.
 * This is the primary entry point for the UI to get the user's identity marker.
 */
@Singleton
class UserProfileManager @Inject constructor(
    private val sessionManager: UserSessionManager,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
) {
    /**
     * Combined flow for Avatar Seed.
     */
    val activeAvatarSeed: Flow<String> = combine(
        authRepository.userData,
        sessionManager.userProfile
    ) { authUser, anonProfile ->
        authUser?.avatarSeed ?: anonProfile.avatarSeed
    }

    /**
     * Combined flow for Avatar Config.
     */
    val activeAvatarConfig: Flow<com.saurabh.artifact.model.AvatarConfig> = combine(
        authRepository.userData,
        sessionManager.userProfile
    ) { authUser, anonProfile ->
        authUser?.avatarConfig ?: anonProfile.avatarConfig
    }

    /**
     * Combined flow for the active username.
     */
    val activeUsername: Flow<String> = combine(
        authRepository.userData,
        sessionManager.userProfile
    ) { authUser, anonProfile ->
        authUser?.anonymousName ?: anonProfile.username
    }

    /**
     * Updates the user's avatar seed.
     */
    suspend fun updateAvatarSeed(seed: String) {
        sessionManager.updateAvatarSeed(seed)
        // TODO: Sync to Firestore
    }

    /**
     * Updates the user's avatar configuration.
     */
    suspend fun updateAvatarConfig(config: com.saurabh.artifact.model.AvatarConfig) {
        sessionManager.updateAvatarConfig(config)
        
        // Sync to Firestore if authenticated
        val userId = authRepository.currentUser.value?.uid
        if (userId != null) {
            try {
                userRepository.updateAvatarConfig(userId, config)
            } catch (e: Exception) {
                android.util.Log.e("UserProfileManager", "Failed to sync avatar config to Firestore", e)
            }
        }
    }

    /**
     * Updates the user's anonymous username.
     */
    suspend fun updateUsername(username: String) {
        // Update local session
        sessionManager.updateUsername(username)
        
        // TODO: Trigger Firestore update if authRepository.currentUser is not null
    }

    suspend fun isUsernameAvailable(username: String): Boolean {
        return userRepository.isUsernameAvailable(username)
    }

    /**
     * Checks if the user is eligible to change their username based on the 30-day cooldown.
     * Returns the number of days remaining if blocked, or 0 if allowed.
     */
    fun getUsernameCooldownDays(user: com.saurabh.artifact.model.User?): Int {
        val lastUpdate = user?.usernameUpdatedAt ?: return 0
        val cooldownMillis = 30 * 24 * 60 * 60 * 1000L
        val diff = com.google.firebase.Timestamp.now().toDate().time - lastUpdate.toDate().time
        
        return if (diff < cooldownMillis) {
            val remaining = cooldownMillis - diff
            (remaining / (24 * 60 * 60 * 1000L)).toInt().coerceAtLeast(1)
        } else {
            0
        }
    }
}
