package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.domain.ArtifactVisibilityFilter
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.worker.IdentitySyncWorker
import android.content.Context
import android.util.Log
import com.saurabh.artifact.model.SigilConfig
import com.saurabh.artifact.service.PersonalizationEngine
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
    private val notificationRepository: NotificationRepository,
    private val personalizationEngine: PersonalizationEngine,
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

        // Background Sync: Listen to remote profile state updates and push them into the local SSOT
        managerScope.launch {
            try {
                authRepository.remoteProfileState.collectLatest { state ->
                    when (state) {
                        is RemoteProfileState.Loading -> {
                            // Invariant: Loading: do not expose a previous account's cached identity.
                            val currentOwner = sessionManager.getOwningUid()
                            if (currentOwner != null && currentOwner != state.uid) {
                                sessionManager.clearIdentityCache(currentOwner)
                                userRepository.invalidateLocalProfile(currentOwner)
                            }
                        }
                        is RemoteProfileState.Exists -> {
                            val currentAuthUid = authRepository.currentUserId
                            if (currentAuthUid == state.uid && state.user.id == state.uid) {
                                sessionManager.syncFromRemote(state.user, expectedUid = state.uid)
                                userRepository.insertProfilePreservingProgression(state.user)
                            }
                        }
                        is RemoteProfileState.CachedAvailable -> {
                            val currentAuthUid = authRepository.currentUserId
                            if (currentAuthUid == state.uid && state.user.id == state.uid) {
                                sessionManager.syncFromRemote(state.user, expectedUid = state.uid)
                                userRepository.insertProfilePreservingProgression(state.user)
                            }
                        }
                        is RemoteProfileState.NotFound -> {
                            // Invariant: NotFound: invoke targeted invalidation only for the matching established account cache.
                            sessionManager.clearIdentityCache(expectedUid = state.uid)
                            userRepository.invalidateLocalProfile(state.uid)
                        }
                        is RemoteProfileState.Unavailable -> {
                            // Invariant: Unavailable: preserve matching local identity cache.
                        }
                        is RemoteProfileState.Unreadable -> {
                            // Invariant: Unreadable: never overwrite valid local identity information with malformed or default model values.
                        }
                        null -> {
                            // No active remote profile state
                        }
                    }
                }
            } catch (e: IllegalStateException) {
                if (e.message?.contains("SharedFlow never completes") == true) {
                    authRepository.userData.collectLatest { firestoreUser ->
                        if (firestoreUser != null) {
                            sessionManager.syncFromRemote(firestoreUser, expectedUid = firestoreUser.id)
                        }
                    }
                } else {
                    throw e
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
     * Updates both username and sigil config atomically.
     */
    suspend fun updateIdentity(username: String?, config: SigilConfig?): Result<Unit> {
        val trimmedUsername = username?.trim()?.takeIf { it.isNotEmpty() }

        if (trimmedUsername == null && config == null) return Result.success(Unit)
        if (trimmedUsername != null && config == null) return updateUsername(trimmedUsername)
        if (trimmedUsername == null && config != null) return updateSigilConfig(config)

        val nonNullUsername = trimmedUsername!!
        val nonNullConfig = config!!

        val userId = authRepository.currentUserId

        // 1. If authenticated, update Firestore first in single transaction
        if (userId.isNotEmpty()) {
            val result = userRepository.createUsername(userId, nonNullUsername, nonNullConfig)
            if (result.isFailure) {
                return result
            }
            IdentitySyncWorker.enqueue(context, userId)
        }

        // 2. Update SSOT DataStore only after remote succeeds
        sessionManager.updateUsername(nonNullUsername, expectedUid = userId.takeIf { it.isNotEmpty() })
        sessionManager.updateSigilConfig(nonNullConfig, expectedUid = userId.takeIf { it.isNotEmpty() })

        // 3. Local author snapshot sync
        if (userId.isNotEmpty()) {
            managerScope.launch {
                val currentProfile = sessionManager.userProfile.first()
                val latestUser = userRepository.getOrCreateProfile().getOrNull()?.user
                val currentVersion = latestUser?.identityMetadata?.identityResetVersion ?: 0L

                artifactRepository.updateLocalAuthorSnapshot(
                    userId = userId,
                    snapshot = AuthorSnapshot(
                        anonymousId = currentProfile.anonymousId,
                        name = trimmedUsername,
                        sigil = currentProfile.sigil,
                        sigilSeed = config.seed,
                        sigilColor = currentProfile.sigilColor,
                        sigilConfig = config
                    ),
                    identityPropagationVersion = currentVersion
                )
            }
        }

        return Result.success(Unit)
    }

    /**
     * Updates the user's sigil configuration.
     */
    suspend fun updateSigilConfig(config: SigilConfig): Result<Unit> {
        val userId = authRepository.currentUserId

        // 1. Sync to Firestore if authenticated first
        if (userId.isNotEmpty()) {
            val result = userRepository.updateSigilConfig(userId, config)
            if (result.isFailure) {
                return result
            }
            IdentitySyncWorker.enqueue(context, userId)
        }

        // 2. Update SSOT DataStore only after remote succeeds
        sessionManager.updateSigilConfig(config, expectedUid = userId.takeIf { it.isNotEmpty() })

        // 3. Local Sync for local artifacts
        if (userId.isNotEmpty()) {
            managerScope.launch {
                ArtifactLogger.d(DiagnosticCategory.PROFILE, "LAUNCHING_LOCAL_SYNC", mapOf("user_id" to userId))
                val currentProfile = sessionManager.userProfile.first()
                val latestUser = userRepository.getOrCreateProfile().getOrNull()?.user
                val currentVersion = latestUser?.identityMetadata?.identityResetVersion ?: 0L
                
                ArtifactLogger.d(DiagnosticCategory.PROFILE, "GOT_PROFILE_FOR_SYNC", mapOf("username" to currentProfile.username))
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
                    identityPropagationVersion = currentVersion
                )
            }
        }

        return Result.success(Unit)
    }

    /**
     * Updates the user's anonymous username.
     */
    suspend fun updateUsername(username: String): Result<Unit> {
        val userId = authRepository.currentUserId
        ArtifactLogger.d(DiagnosticCategory.PROFILE, "UPDATE_USERNAME_STARTED", mapOf("user_id" to userId, "username" to username))

        // 1. If authenticated, update Firestore first
        if (userId.isNotEmpty()) {
            val result = userRepository.createUsername(userId, username)
            if (result.isFailure) {
                return result
            }
            IdentitySyncWorker.enqueue(context, userId)
        }

        // 2. Update SSOT DataStore only after remote succeeds
        sessionManager.updateUsername(username, expectedUid = userId.takeIf { it.isNotEmpty() })

        // 3. Local Sync for local artifacts
        if (userId.isNotEmpty()) {
            managerScope.launch {
                ArtifactLogger.d(DiagnosticCategory.PROFILE, "LAUNCHING_LOCAL_SYNC", mapOf("user_id" to userId))
                val currentProfile = sessionManager.userProfile.first()
                val latestUser = userRepository.getOrCreateProfile().getOrNull()?.user
                val currentVersion = latestUser?.identityMetadata?.identityResetVersion ?: 0L

                ArtifactLogger.d(DiagnosticCategory.PROFILE, "GOT_PROFILE_FOR_SYNC", mapOf("username" to currentProfile.username))
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
                    identityPropagationVersion = currentVersion
                )
            }
        }

        return Result.success(Unit)
    }

    suspend fun isUsernameAvailable(username: String): Result<Boolean> {
        return userRepository.isUsernameAvailable(username)
    }
}
