package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.domain.ArtifactVisibilityFilter
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dagger.Lazy
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
    private val visibilityFilter: Lazy<ArtifactVisibilityFilter>,
    @com.saurabh.artifact.di.ApplicationScope internal val managerScope: CoroutineScope
) {

    /**
     * Cancel all background tasks. Should only be called during testing or app shutdown.
     */
    internal fun cancelScope() {
        managerScope.cancel()
    }

    private var safetySyncJob: kotlinx.coroutines.Job? = null

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
     * Explicitly initializes the cross-device safety synchronization for the given user.
     * This ensures suppression markers are synchronized before discovery surfaces are shown.
     * Manages its own lifecycle to prevent duplicate listeners or cross-account leakage.
     */
    fun initializeSafetySync(userId: String) {
        if (userId.isEmpty()) return
        
        // Stop any existing sync to ensure clean boundary for account swaps
        stopSafetySync()
        
        Log.i("UserProfileManager", "Initializing Safety Sync for $userId")
        safetySyncJob = managerScope.launch {
            visibilityFilter.get().syncReportsFromRemote(userId, managerScope).collect()
        }
    }

    /**
     * Stops any active safety synchronization.
     */
    fun stopSafetySync() {
        if (safetySyncJob?.isActive == true) {
            Log.d("UserProfileManager", "Stopping active Safety Sync")
            safetySyncJob?.cancel()
        }
        safetySyncJob = null
    }

    /**
     * SSOT flow for the entire user profile.
     */
    val userProfile: Flow<com.saurabh.artifact.model.UserProfile> = sessionManager.userProfile

    /**
     * SSOT flow for Sigil Config.
     */
    val activeSigilConfig: Flow<com.saurabh.artifact.model.SigilConfig> = sessionManager.userProfile.map { it.sigilConfig }

    /**
     * SSOT flow for the active username.
     */
    val activeUsername: Flow<String> = sessionManager.userProfile.map { it.username }

    /**
     * Phase 2: Explicitly reconciles identity propagation state.
     * Schedules IdentitySyncWorker if identityResetVersion > lastCompletedIdentityVersion.
     * Uses KEEP policy to avoid restarting work that is already in progress.
     */
    fun reconcileIdentitySync(user: com.saurabh.artifact.model.User) {
        if (user.identityMetadata.isIdentitySyncPending) {
            android.util.Log.i("UserProfileManager", "Detected pending identity sync for ${user.id}. Scheduling recovery.")
            IdentitySyncWorker.enqueue(
                context = context,
                userId = user.id,
                version = user.identityMetadata.identityResetVersion,
                policy = androidx.work.ExistingWorkPolicy.KEEP
            )
        }
    }

    /**
     * Phase 2: Starts active monitoring of identity propagation state.
     * Triggers reconciliation on initial load and whenever version fields change.
     */
    fun startIdentityMonitoring() {
        managerScope.launch {
            authRepository.userData
                .filterNotNull()
                .distinctUntilChanged { old, new ->
                    old.identityMetadata.identityResetVersion == new.identityMetadata.identityResetVersion &&
                    old.identityMetadata.lastCompletedIdentityVersion == new.identityMetadata.lastCompletedIdentityVersion
                }
                .collect { user ->
                    reconcileIdentitySync(user)
                }
        }
    }

    /**
     * Updates the user's sigil configuration.
     */
    suspend fun updateSigilConfig(config: com.saurabh.artifact.model.SigilConfig): Result<Unit> {
        // 1. Update SSOT immediately
        sessionManager.updateSigilConfig(config)
        
        val userId = authRepository.currentUserId

        // 2. Optimistic Local Sync
        if (userId.isNotEmpty()) {
            managerScope.launch {
                Log.d("UserProfileManager", "Launching local sync for $userId")
                val currentProfile = sessionManager.userProfile.first()
                val latestUser = userRepository.getOrCreateProfile().getOrNull()?.user
                val currentVersion = latestUser?.identityMetadata?.identityResetVersion ?: 0L
                
                Log.d("UserProfileManager", "Got profile for sync: ${currentProfile.username}")
                artifactRepository.updateLocalAuthorSnapshot(
                    userId = userId,
                    snapshot = AuthorSnapshot(
                        anonymousId = currentProfile.anonymousId,
                        name = currentProfile.username,
                        sigil = currentProfile.sigil,
                        sigilSeed = config.seed,
                        sigilColor = currentProfile.sigilColor,
                        sigilConfig = config
                    ),
                    identityVersion = currentVersion
                )
            }
        }

        // 3. Sync to Firestore if authenticated (Eventual Consistency)
        if (userId.isNotEmpty()) {
            val result = userRepository.updateSigilConfig(userId, config)
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
                val latestUser = userRepository.getOrCreateProfile().getOrNull()?.user
                val currentVersion = latestUser?.identityMetadata?.identityResetVersion ?: 0L

                Log.d("UserProfileManager", "Got profile for sync: ${currentProfile.username}")
                artifactRepository.updateLocalAuthorSnapshot(
                    userId = userId,
                    snapshot = AuthorSnapshot(
                        anonymousId = currentProfile.anonymousId,
                        name = username,
                        sigil = currentProfile.sigil,
                        sigilSeed = currentProfile.sigilSeed,
                        sigilColor = currentProfile.sigilColor,
                        sigilConfig = currentProfile.sigilConfig
                    ),
                    identityVersion = currentVersion
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
            val newVersion = result.getOrThrow()
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
                            sigilSeed = updatedProfile.sigilSeed,
                            sigilColor = updatedProfile.sigilColor,
                            sigilConfig = updatedProfile.sigilConfig
                        ),
                        identityVersion = newVersion
                    )
                    Log.i("UserProfileManager", "Local identity synchronization completed for $userId")
                } catch (e: Exception) {
                    Log.e("UserProfileManager", "Local identity synchronization failed for $userId", e)
                }
            }
            return Result.success(Unit)
        } else {
            Log.e("UserProfileManager", "Emergency reset orchestration aborted due to remote failure")
            return Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
        }
    }
}
