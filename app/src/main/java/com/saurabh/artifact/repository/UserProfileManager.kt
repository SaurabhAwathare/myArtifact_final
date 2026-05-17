package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.model.AvatarConfig
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
    authRepository: AuthRepository,
    private val userRepository: UserRepository,
) {
    /**
     * Combined flow that prioritizes authenticated user data but falls back to anonymous.
     */
    val activeUserEmoji: Flow<String> = combine(
        authRepository.userData,
        sessionManager.userProfile
    ) { authUser, anonProfile ->
        // If logged in, use the cloud-synced emoji; otherwise, use local
        authUser?.identityEmoji ?: anonProfile.identityEmoji
    }

    /**
     * Combined flow that prioritizes authenticated user data (displayName) but falls back to anonymous username.
     */
    val activeUsername: Flow<String> = combine(
        authRepository.userData,
        sessionManager.userProfile
    ) { authUser, anonProfile ->
        // Priority: 1. Firebase Auth User Data, 2. Local Anonymous Username
        authUser?.displayName ?: anonProfile.username
    }

    /**
     * Combined flow for Avatar Configuration.
     */
    val activeAvatarConfig: Flow<AvatarConfig?> = combine(
        authRepository.userData,
        sessionManager.userProfile
    ) { authUser, anonProfile ->
        // In the future, authUser might have avatar config as well
        anonProfile.avatarConfig
    }

    /**
     * Updates the user's avatar configuration.
     */
    suspend fun updateAvatarConfig(config: AvatarConfig) {
        sessionManager.updateAvatarConfig(config)
        // TODO: Sync to Firestore
    }

    /**
     * Updates the user's identity marker. 
     * In the future, this will also trigger a sync to Firestore if authenticated.
     */
    suspend fun updateIdentityEmoji(emoji: String) {
        // Update local session
        sessionManager.updateEmoji(emoji)
        
        // TODO: Trigger Firestore update if authRepository.currentUser is not null
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
     * Checks if the user is eligible to change their username based on the 7-day cooldown.
     * Returns the number of days remaining if blocked, or 0 if allowed.
     */
    fun getUsernameCooldownDays(user: com.saurabh.artifact.model.User?): Int {
        val lastUpdate = user?.usernameUpdatedAt ?: return 0
        val cooldownMillis = 7 * 24 * 60 * 60 * 1000L
        val diff = com.google.firebase.Timestamp.now().toDate().time - lastUpdate.toDate().time
        
        return if (diff < cooldownMillis) {
            val remaining = cooldownMillis - diff
            (remaining / (24 * 60 * 60 * 1000L)).toInt().coerceAtLeast(1)
        } else {
            0
        }
    }
}
