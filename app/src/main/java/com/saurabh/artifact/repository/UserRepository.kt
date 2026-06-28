package com.saurabh.artifact.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FieldPath
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.User
import com.saurabh.artifact.model.AvatarConfig
import com.saurabh.artifact.model.avatar.*
import com.saurabh.artifact.model.UserPrivateSettings
import com.saurabh.artifact.util.SecureString
import com.saurabh.artifact.util.UsernameGenerator
import com.saurabh.artifact.data.local.UserDao
import com.saurabh.artifact.data.local.UserLocalEntity
import android.util.Log
import android.content.Context
import com.saurabh.artifact.worker.IdentitySyncWorker
import com.saurabh.artifact.util.ArtifactLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlin.time.Duration.Companion.seconds

@Singleton
class UserRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val userDao: UserDao,
    private val identityProtectionPolicy: com.saurabh.artifact.domain.IdentityProtectionPolicy,
    private val profileRepairService: com.saurabh.artifact.domain.auth.ProfileRepairService,
    private val registrationCoordinator: Lazy<com.saurabh.artifact.domain.auth.RegistrationCoordinator>,
    private val pendingInteractionDao: com.saurabh.artifact.data.local.PendingInteractionDao
) {
    private val usersCollection = firestore.collection("users")
    private val usernamesCollection = firestore.collection("usernames")

    /**
     * Returns the current authenticated user's ID.
     */
    fun getCurrentUserId(): String? = auth.currentUser?.uid

    /**
     * Creates or updates a unique username for the user.
     * Uses a transaction to ensure uniqueness across the platform.
     */
    suspend fun createUsername(userId: String, username: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext Result.failure(AppError.InvalidInput("User ID cannot be blank"))
        
        // SELF-HEALING: Ensure profile exists before update
        registrationCoordinator.get().ensureProfileExists()

        val normalizedUsername = username.lowercase().trim()
        try {
            val userRef = try {
                usersCollection.document(userId.trim())
            } catch (e: Exception) {
                return@withContext Result.failure(AppError.from(e))
            }

            val userSnapshot = userRef.get().await()
            val (user, _) = profileRepairService.loadAndRepair(userSnapshot)
            
            val isWithinWindow = identityProtectionPolicy.isWithinWindow(user.identityMetadata.lastIdentityChangeAt)
            val newCount = if (isWithinWindow) user.identityMetadata.identityChangeCount30Days + 1 else 1

            firestore.runTransaction { transaction ->
                val usernameRef = usernamesCollection.document(normalizedUsername)

                // 1. Check if the username is already taken
                val usernameDoc = transaction[usernameRef]
                if (usernameDoc.exists()) {
                    val existingUserId = usernameDoc.getString("uid") ?: usernameDoc.getString("userId")
                    if (existingUserId != userId) {
                        throw AppError.UsernameTaken(normalizedUsername)
                    }
                }

                // 2. Get current user to find old username for cleanup
                val userDoc = transaction[userRef]
                val oldUsername = userDoc.getString("anonymousName")?.lowercase()?.trim()

                // 3. Reserve the new username
                transaction[usernameRef] = mapOf(
                    "uid" to userId,
                    "createdAt" to FieldValue.serverTimestamp()
                )

                // 4. Update the user profile
                transaction.update(
                    userRef, mapOf(
                        "anonymousName" to username,
                        "isAnonymous" to false,
                        "usernameUpdatedAt" to FieldValue.serverTimestamp(),
                        "identityMetadata.lastIdentityChangeAt" to FieldValue.serverTimestamp(),
                        "identityMetadata.identityChangeCount30Days" to newCount
                ))

                // 5. Clean up old username reservation
                if ((oldUsername != null) && (oldUsername != normalizedUsername)) {
                    transaction.delete(usernamesCollection.document(oldUsername))
                }
            }.await()

            // Zero-Trust: Notification handled by backend (optional/future)
            // notificationRepository.createNotification(
            //     userId = userId,
            //     message = "USERNAME_UPDATED|$username"
            // )

            // Update cache
            getCachedProfile()?.let { cached ->
                userDao.insertProfile(mapUserToLocal(cached.copy(anonymousName = username, isAnonymous = false)))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("UserRepository", "createUsername failed: userId=$userId, username=$username", e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Checks if a username is available in Firestore.
     * Lightweight read-only check.
     */
    suspend fun isUsernameAvailable(username: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext Result.success(true)
        try {
            val doc = usernamesCollection.document(username.lowercase().trim()).get().await()
            Result.success(!doc.exists())
        } catch (e: Exception) {
            Log.e("UserRepository", "Error checking username availability", e)
            Result.failure(AppError.from(e))
        }
    }

    suspend fun getOrCreateProfile(): Result<ProfileResult> = withContext(Dispatchers.IO) {
        // 1. Ensure Auth
        val initialUser = auth.currentUser ?: return@withContext Result.failure(AppError.Unauthenticated())
        
        try {
            Log.d("APP_FLOW", "REGISTRATION_BEGIN: users/${initialUser.uid}")
            try {
                withTimeout(5.seconds) {
                    initialUser.reload().await()
                }
            } catch (e: Exception) {
                Log.w("UserRepository", "Failed to reload user or timeout reached", e)
                if (e !is kotlinx.coroutines.TimeoutCancellationException) {
                    auth.signOut()
                    return@withContext Result.failure(AppError.from(e))
                }
            }

            val currentUser = auth.currentUser ?: return@withContext Result.failure(AppError.Unauthenticated())
            val userRef = usersCollection.document(currentUser.uid)
            val privateRef = userRef.collection("private").document("settings")

            // 2. Atomic Check & Create via Transaction
            val profileResult = withTimeout(15.seconds) {
                firestore.runTransaction { transaction ->
                    val snapshot = transaction[userRef]
                    
                    if (snapshot.exists()) {
                        Log.d("UserRepository", "User exists, validating schema...")
                        
                        val (user, needsRepair) = profileRepairService.loadAndRepair(snapshot)
                        
                        val privateSnapshot = transaction[privateRef]
                        val privateMissing = !privateSnapshot.exists()

                        if (needsRepair || privateMissing) {
                            Log.i("APP_FLOW", "HEALING_PROFILE | UID: ${currentUser.uid} | profileRepair=$needsRepair | privateRepair=$privateMissing")
                            if (needsRepair) transaction.set(userRef, user)
                            
                            if (privateMissing) {
                                val defaultPrivate = UserPrivateSettings(
                                    secureEmail = SecureString.fromString(currentUser.email ?: ""),
                                    secureRealName = SecureString.fromString(currentUser.displayName ?: ""),
                                    isAdmin = false,
                                    accountStatus = "ACTIVE"
                                )
                                transaction[privateRef] = defaultPrivate
                            }
                        }

                        ProfileResult(user = user, isNewUser = false)
                    } else {
                        Log.d("UserRepository", "New User initialization")
                        val anonymousId = "usr_${java.util.UUID.randomUUID().toString().take(5).uppercase()}"
                        val anonymousName = UsernameGenerator.generate()
                        val anonymousSigil = UsernameGenerator.deriveSigil(anonymousId)
                        val seed = java.util.UUID.randomUUID().toString()
                        
                        val newProfile = User(
                            id = currentUser.uid,
                            anonymousId = anonymousId,
                            anonymousName = anonymousName,
                            anonymousSigil = anonymousSigil,
                            avatarSeed = seed,
                            avatarConfig = AvatarConfig(
                                seed = seed, 
                                theme = "CARTOON",
                                faceShape = FaceShape.entries.random(),
                                eyeType = EyeType.entries.random(),
                                mouthType = MouthType.entries.random(),
                                hairType = HairType.entries.random(),
                                accessoryType = AccessoryType.entries.random()
                            ),
                            isAnonymous = true,
                            emotionalProfile = "New Soul"
                        )

                        val privateSettings = UserPrivateSettings(
                            secureEmail = SecureString.fromString(currentUser.email ?: ""),
                            secureRealName = SecureString.fromString(currentUser.displayName ?: ""),
                            isAdmin = false,
                            accountStatus = "ACTIVE"
                        )

                        transaction[userRef] = newProfile
                        transaction[privateRef] = privateSettings
                        
                        ProfileResult(user = newProfile, isNewUser = true)
                    }
                }.await()
            }
            
            Log.d("APP_FLOW", "REGISTRATION_SUCCESS: isNewUser=${profileResult.isNewUser}")
            
            // Cache the profile locally
            try {
                userDao.insertProfile(mapUserToLocal(profileResult.user))
            } catch (e: Exception) {
                Log.e("UserRepository", "Failed to cache user profile locally", e)
            }

            Result.success(profileResult)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e("APP_FLOW", "REGISTRATION_FAILED: TIMEOUT", e)
            Result.failure(AppError.from(e))
        } catch (e: Exception) {
            Log.e("APP_FLOW", "REGISTRATION_FAILED", e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Fetches the user profile from the local cache.
     * Useful for offline-first scenarios or when network is unavailable.
     */
    suspend fun getCachedProfile(): User? = withContext(Dispatchers.IO) {
        val currentUserId = auth.currentUser?.uid ?: return@withContext null
        return@withContext try {
            userDao.getProfile(currentUserId)?.let { mapLocalToUser(it) }
        } catch (e: Exception) {
            Log.e("UserRepository", "Error fetching cached profile", e)
            null
        }
    }

    private fun mapUserToLocal(user: User): UserLocalEntity {
        return UserLocalEntity(
            id = user.id,
            anonymousId = user.anonymousId,
            anonymousName = user.anonymousName,
            anonymousSigil = user.anonymousSigil,
            avatarSeed = user.avatarSeed,
            avatarColor = user.avatarColor,
            avatarConfigJson = kotlinx.serialization.json.Json.encodeToString(user.avatarConfig)
        )
    }

    private fun mapLocalToUser(local: UserLocalEntity): User {
        return User(
            id = local.id,
            anonymousId = local.anonymousId,
            anonymousName = local.anonymousName,
            anonymousSigil = local.anonymousSigil,
            avatarSeed = local.avatarSeed,
            avatarColor = local.avatarColor,
            avatarConfig = try {
                kotlinx.serialization.json.Json.decodeFromString(local.avatarConfigJson)
            } catch (_: Exception) {
                AvatarConfig(seed = local.avatarSeed)
            }
        )
    }

    /**
     * Streams the user profile in real-time from Firestore.
     * Refactored for production stability and crash prevention.
     */
    fun streamUserProfile(userId: String?): Flow<User?> = callbackFlow {
        // 1. Defensive Validation
        if (userId.isNullOrBlank()) {
            Log.w("UserRepository", "streamUserProfile: Received null/blank userId. Emitting null.")
            trySend(null)
            close()
            return@callbackFlow
        }

        // 2. Resource Reference Validation
        val docRef = try {
            usersCollection.document(userId.trim())
        } catch (e: Exception) {
            Log.e("UserRepository", "streamUserProfile: Invalid path for userId: $userId", e)
            trySend(null)
            close(e)
            return@callbackFlow
        }

        // 3. Listener Implementation
        val registration = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e("UserRepository", "Error streaming profile for $userId: ${error.code}", error)
                // HARDENING: If it's a permanent error (Permission Denied), we emit null and close.
                // However, we MUST trySend(null) first to unblock any 'combine' operators.
                trySend(null)
                if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    close(error)
                }
                return@addSnapshotListener
            }

            if ((snapshot != null) && snapshot.exists()) {
                try {
                    val (user, _) = profileRepairService.loadAndRepair(snapshot)
                    trySend(user)
                } catch (e: Exception) {
                    Log.e("UserRepository", "Parsing error for user $userId", e)
                    trySend(null)
                }
            } else {
                Log.i("UserRepository", "User profile $userId does not exist or was deleted.")
                trySend(null)
            }
        }

        // 4. Graceful Cleanup
        awaitClose {
            Log.d("UserRepository", "Closing stream for $userId")
            registration.remove()
        }
    }.catch { e ->
        Log.e("UserRepository", "Flow crashed in streamUserProfile", e)
        emit(null)
    }

    /**
     * Establishes a resonance relationship between two presences atomically.
     * PUBLIC API: Used by ViewModels. Enqueues interaction if unified queue is enabled.
     */
    suspend fun resonateWithUser(currentUserId: String, targetUserId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (currentUserId.isBlank() || targetUserId.isBlank()) {
            return@withContext Result.failure(AppError.InvalidInput("User IDs cannot be blank"))
        }
        if (currentUserId == targetUserId) return@withContext Result.failure(Exception("Cannot resonate with yourself"))

        try {
            val pending = com.saurabh.artifact.data.local.PendingInteractionEntity(
                userId = currentUserId,
                artifactId = targetUserId, // Using artifactId field for targetUserId
                interactionType = com.saurabh.artifact.data.local.InteractionType.FOLLOW,
                action = com.saurabh.artifact.data.local.InteractionAction.ADD,
                metadata = currentUserId
            )
            pendingInteractionDao.deleteByType(targetUserId, currentUserId, com.saurabh.artifact.data.local.InteractionType.FOLLOW)
            pendingInteractionDao.insert(pending)
            com.saurabh.artifact.worker.InteractionSyncWorker.enqueue(context)
            
            ArtifactLogger.i("UserRepository", "Follow interaction queued locally for $targetUserId")
            Result.success(Unit)
        } catch (e: Exception) {
            ArtifactLogger.e("UserRepository", "Failed to queue follow interaction", e)
            Result.failure(e)
        }
    }

    /**
     * Removes a resonance relationship between two presences atomically.
     * PUBLIC API: Used by ViewModels. Enqueues interaction if unified queue is enabled.
     */
    suspend fun stopResonatingWithUser(currentUserId: String, targetUserId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (currentUserId.isBlank() || targetUserId.isBlank()) {
            return@withContext Result.failure(AppError.InvalidInput("User IDs cannot be blank"))
        }

        try {
            val pending = com.saurabh.artifact.data.local.PendingInteractionEntity(
                userId = currentUserId,
                artifactId = targetUserId,
                interactionType = com.saurabh.artifact.data.local.InteractionType.FOLLOW,
                action = com.saurabh.artifact.data.local.InteractionAction.REMOVE,
                metadata = currentUserId
            )
            pendingInteractionDao.deleteByType(targetUserId, currentUserId, com.saurabh.artifact.data.local.InteractionType.FOLLOW)
            pendingInteractionDao.insert(pending)
            com.saurabh.artifact.worker.InteractionSyncWorker.enqueue(context)
            
            ArtifactLogger.i("UserRepository", "Unfollow interaction queued locally for $targetUserId")
            Result.success(Unit)
        } catch (e: Exception) {
            ArtifactLogger.e("UserRepository", "Failed to queue unfollow interaction", e)
            Result.failure(e)
        }
    }

    /**
     * Internal synchronization method for follow.
     * INTERNAL SYNC API: Intended exclusively for InteractionSyncWorker.
     * Performs direct Firestore write without enqueuing.
     */
    internal suspend fun syncFollowToFirestore(currentUserId: String, targetUserId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val intentRef = usersCollection.document(currentUserId)
                .collection("private").document("intents")
                .collection("follow").document(targetUserId)
            
            intentRef.set(mapOf(
                "targetUserId" to targetUserId,
                "action" to "FOLLOW",
                "timestamp" to FieldValue.serverTimestamp(),
                "version" to 1
            )).await()
            
            ArtifactLogger.i("UserRepository", "Follow intent created for $targetUserId")
            Result.success(Unit)
        } catch (e: Exception) {
            ArtifactLogger.e("UserRepository", "Failed to create follow intent", e)
            Result.failure(e)
        }
    }

    /**
     * Internal synchronization method for unfollow.
     * INTERNAL SYNC API: Intended exclusively for InteractionSyncWorker.
     * Performs direct Firestore write without enqueuing.
     */
    internal suspend fun syncUnfollowFromFirestore(currentUserId: String, targetUserId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val intentRef = usersCollection.document(currentUserId)
                .collection("private").document("intents")
                .collection("follow").document(targetUserId)
            
            intentRef.delete().await()
            
            ArtifactLogger.i("UserRepository", "Follow intent removed for $targetUserId")
            Result.success(Unit)
        } catch (e: Exception) {
            ArtifactLogger.e("UserRepository", "Failed to remove follow intent", e)
            Result.failure(e)
        }
    }

    /**
     * Streams the resonance relationship status between two users.
     * Upgraded to be fully reactive across both modern and legacy collections.
     */
    fun observeIsResonating(currentUserId: String, targetUserId: String): Flow<Boolean> {
        if (currentUserId.isBlank() || targetUserId.isBlank()) {
            return flowOf(value = false)
        }

        val modernRef = usersCollection.document(currentUserId.trim())
            .collection("resonance_out").document(targetUserId.trim())

        val legacyRef = usersCollection.document(currentUserId.trim())
            .collection("following").document(targetUserId.trim())

        return combine(
            observeDocumentExists(modernRef),
            observeDocumentExists(legacyRef)
        ) { modern, legacy ->
            modern || legacy
        }.distinctUntilChanged()
    }

    private fun observeDocumentExists(docRef: DocumentReference): Flow<Boolean> = callbackFlow {
        val registration = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                // HARDENING: Do not crash or hang on error (e.g. Permission Denied)
                // Just assume document doesn't exist/isn't accessible
                trySend(element = false)
                return@addSnapshotListener
            }
            trySend(element = snapshot?.exists() ?: false)
        }
        awaitClose { registration.remove() }
    }

    /**
     * Checks if the current user is resonating with the target user.
     */
    suspend fun isResonating(currentUserId: String, targetUserId: String): Boolean {
        if (currentUserId.isBlank() || targetUserId.isBlank()) return false
        return try {
            val doc = usersCollection.document(currentUserId.trim())
                .collection("resonance_out").document(targetUserId.trim())
                .get().await()
            if (doc.exists()) return true
            
            // Fallback to legacy
            usersCollection.document(currentUserId.trim())
                .collection("following").document(targetUserId.trim())
                .get().await().exists()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun updateAvatarConfig(userId: String, config: AvatarConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // SELF-HEALING: Ensure profile exists before update
            registrationCoordinator.get().ensureProfileExists()

            val userRef = usersCollection.document(userId)
            val userSnapshot = userRef.get().await()
            val (user, _) = profileRepairService.loadAndRepair(userSnapshot)
            
            val isWithinWindow = identityProtectionPolicy.isWithinWindow(user.identityMetadata.lastIdentityChangeAt)
            val newCount = if (isWithinWindow) user.identityMetadata.identityChangeCount30Days + 1 else 1

            userRef.update(
                mapOf(
                    "avatarConfig" to config,
                    "usernameUpdatedAt" to FieldValue.serverTimestamp(),
                    "identityMetadata.lastIdentityChangeAt" to FieldValue.serverTimestamp(),
                    "identityMetadata.identityChangeCount30Days" to newCount
                )
            ).await()

            // Zero-Trust: Notification handled by backend (optional/future)
            // notificationRepository.createNotification(
                // userId = userId,
                // message = "AVATAR_UPDATED"
            // )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("UserRepository", "updateAvatarConfig failed", e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Immediately randomizes the user's identity for emergency privacy protection.
     * Hardened with a version-based state machine and atomic transaction.
     */
    suspend fun emergencyIdentityReset(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext Result.failure(AppError.InvalidInput("User ID cannot be blank"))

        try {
            val userRef = usersCollection.document(userId)
            
            val (newName, newVersion) = firestore.runTransaction { transaction ->
                val userSnapshot = transaction[userRef]
                val (user, _) = profileRepairService.loadAndRepair(userSnapshot)

                val oldName = user.anonymousName.lowercase().trim()
                val generatedName = UsernameGenerator.generate()
                val normalizedNewName = generatedName.lowercase().trim()
                
                val currentVersion = user.identityMetadata.identityResetVersion
                val nextVersion = currentVersion + 1

                // 1. Reserve new username
                val newUsernameRef = usernamesCollection.document(normalizedNewName)
                transaction[newUsernameRef] = mapOf(
                    "uid" to userId,
                    "createdAt" to FieldValue.serverTimestamp()
                )

                // 2. Delete old username reservation
                if (oldName.isNotEmpty()) {
                    transaction.delete(usernamesCollection.document(oldName))
                }

                // 3. Update user profile
                val newSeed = java.util.UUID.randomUUID().toString()
                val updateMap = mapOf(
                    "anonymousName" to generatedName,
                    "anonymousSigil" to UsernameGenerator.deriveSigil(user.anonymousId),
                    "avatarSeed" to newSeed,
                    "avatarConfig" to user.avatarConfig.copy(seed = newSeed),
                    "usernameUpdatedAt" to FieldValue.serverTimestamp(),
                    "identityMetadata.lastIdentityChangeAt" to FieldValue.serverTimestamp(),
                    "identityMetadata.emergencyResetCount" to FieldValue.increment(1),
                    "identityMetadata.identityResetVersion" to nextVersion,
                    "identityMetadata.resetStartedAt" to FieldValue.serverTimestamp()
                )
                transaction.update(userRef, updateMap)

                generatedName to nextVersion
            }.await()

            // 4. Update local profile cache (Optimistic)
            getCachedProfile()?.let { user ->
                val newSeed = java.util.UUID.randomUUID().toString()
                val updatedUser = user.copy(
                    anonymousName = newName,
                    avatarSeed = newSeed,
                    avatarConfig = user.avatarConfig.copy(seed = newSeed),
                    identityMetadata = user.identityMetadata.copy(
                        emergencyResetCount = user.identityMetadata.emergencyResetCount + 1,
                        identityResetVersion = newVersion,
                        resetStartedAt = com.google.firebase.Timestamp.now()
                    )
                )
                userDao.insertProfile(mapUserToLocal(updatedUser))
            }

            // Zero-Trust: Notification handled by backend (optional/future)
            // notificationRepository.createNotification(
            //     userId = userId,
            //     message = "IDENTITY_PROTECTED|$newName"
            // )

            // Log moderation event
            val reportRef = firestore.collection("reports").document()
            reportRef.set(mapOf(
                "type" to "EMERGENCY_RESET",
                "userId" to userId,
                "reporterId" to userId,
                "timestamp" to FieldValue.serverTimestamp(),
                "reason" to "USER_TRIGGERED_PRIVACY_PROTECTION",
                "version" to newVersion
            )).await()

            // Trigger global identity synchronization
            IdentitySyncWorker.enqueue(context, userId, newVersion)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("UserRepository", "emergencyIdentityReset failed", e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Reports an identity exposure (doxxing) incident.
     */
    suspend fun reportIdentityExposure(
        reporterId: String,
        reportedUserId: String,
        artifactId: String?,
        commentId: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val reportRef = firestore.collection("reports").document()
            reportRef.set(mapOf(
                "type" to "IDENTITY_EXPOSURE",
                "priority" to "CRITICAL",
                "reporterId" to reporterId,
                "reportedUserId" to reportedUserId,
                "artifactId" to artifactId,
                "commentId" to commentId,
                "timestamp" to FieldValue.serverTimestamp(),
                "status" to "PENDING"
            )).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("UserRepository", "reportIdentityExposure failed", e)
            Result.failure(e)
        }
    }

    /**
     * Blocks a user from future interactions.
     */
    suspend fun blockUser(userId: String, targetUserId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val blockRef = usersCollection.document(userId).collection("private").document("blocks")
                .collection("users").document(targetUserId)
            
            blockRef.set(mapOf(
                "timestamp" to FieldValue.serverTimestamp()
            )).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("UserRepository", "blockUser failed", e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Fetches a paginated list of users who are either "resonators" (resonance_in)
     * or being "resonated with" (resonance_out) by the target user.
     */
    suspend fun getResonanceUsers(
        userId: String,
        type: String, // "resonance_in" or "resonance_out"
        limit: Int = 20,
        lastVisible: DocumentSnapshot? = null
    ): Result<Pair<List<User>, DocumentSnapshot?>> {
        return withContext(Dispatchers.IO) {
            try {
                var query = usersCollection.document(userId.trim())
                    .collection(type)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(limit.toLong())

                lastVisible?.let { query = query.startAfter(it) }

                val snapshot = query.get().await()
                if (snapshot.isEmpty) return@withContext Result.success(emptyList<User>() to null)

                val userIds = snapshot.documents.map { it.id }

                // Batch fetch User documents
                // Note: whereIn has a limit of 10-30 depending on Firebase version, but typically 10 for many SDKs.
                // We'll chunk if needed, but for a 20 limit we might need 2 chunks of 10.
                val userChunks = userIds.chunked(10)
                val users = mutableListOf<User>()

                for (chunk in userChunks) {
                    val userSnapshot =
                        usersCollection.whereIn(FieldPath.documentId(), chunk).get().await()
                    users.addAll(
                        userSnapshot.documents.map { doc ->
                            profileRepairService.loadAndRepair(doc).first
                        })
                }

                // Ensure order matches the resonance timestamp order
                val orderedUsers = userIds.mapNotNull { id -> users.find { it.id == id } }

                Result.success(orderedUsers to snapshot.documents.lastOrNull())
            } catch (e: Exception) {
                Log.e("UserRepository", "getResonanceUsers failed for $userId ($type)", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Increments the artifact count for a user.
     */
    suspend fun incrementArtifactsCount(userId: String) = withContext(Dispatchers.IO) {
        try {
            usersCollection.document(userId).update("artifactsCount", FieldValue.increment(1)).await()
        } catch (e: Exception) {
            Log.e("UserRepository", "Failed to increment artifactsCount for $userId", e)
        }
    }

    /**
     * Decrements the artifact count for a user.
     */
    suspend fun decrementArtifactsCount(userId: String) = withContext(Dispatchers.IO) {
        try {
            usersCollection.document(userId).update("artifactsCount", FieldValue.increment(-1)).await()
        } catch (e: Exception) {
            Log.e("UserRepository", "Failed to decrement artifactsCount for $userId", e)
        }
    }
}
