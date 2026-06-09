package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.UserSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
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
    private val sessionManager: UserSessionManager,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        
        // 2. Sync to Firestore if authenticated (Eventual Consistency)
        val userId = authRepository.currentUser.value?.uid
        if (userId != null) {
            return userRepository.updateAvatarConfig(userId, config)
        }
        return Result.success(Unit)
    }

    /**
     * Updates the user's anonymous username.
     */
    suspend fun updateUsername(username: String): Result<Unit> {
        // 1. Update SSOT immediately
        sessionManager.updateUsername(username)
        
        // 2. Sync to Firestore if authenticated (Eventual Consistency)
        val userId = authRepository.currentUser.value?.uid
        if (userId != null) {
            return userRepository.createUsername(userId, username)
        }
        return Result.success(Unit)
    }

    suspend fun isUsernameAvailable(username: String): Result<Boolean> {
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
