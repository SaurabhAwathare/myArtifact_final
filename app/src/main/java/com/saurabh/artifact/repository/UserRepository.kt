package com.saurabh.artifact.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FieldPath
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.User
import com.saurabh.artifact.model.SigilConfig
import com.saurabh.artifact.model.UserPrivateSettings
import com.saurabh.artifact.util.SecureString
import com.saurabh.artifact.util.UsernameGenerator
import com.saurabh.artifact.data.local.UserDao
import com.saurabh.artifact.data.local.UserLocalEntity
import android.content.Context
import com.saurabh.artifact.worker.IdentitySyncWorker
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val userDao: Lazy<UserDao>,
    private val identityProtectionPolicy: com.saurabh.artifact.domain.IdentityProtectionPolicy,
    private val registrationCoordinator: Lazy<com.saurabh.artifact.domain.auth.RegistrationCoordinator>,
    private val pendingInteractionDao: Lazy<com.saurabh.artifact.data.local.PendingInteractionDao>,
    private val ignoredUserDao: Lazy<com.saurabh.artifact.data.local.IgnoredUserDao>,
    private val diagnosticLogger: DiagnosticLogger
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
            val user = userSnapshot.toObject(User::class.java)?.copy(id = userSnapshot.id)
                ?: return@withContext Result.failure(AppError.NotFound("User", userId))
            
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
                        "identityMetadata.identityChangeCount30Days" to newCount,
                        "identityMetadata.identityResetVersion" to FieldValue.increment(1) // Trigger backend propagation
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
                userDao.get().insertProfile(mapUserToLocal(cached.copy(anonymousName = username, isAnonymous = false)))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "USERNAME_CREATE_FAILED", mapOf(LogKeys.USER_ID to userId, "username" to username), e)
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
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "USERNAME_AVAILABILITY_CHECK_FAILED", mapOf("username" to username), e)
            Result.failure(AppError.from(e))
        }
    }

    suspend fun getOrCreateProfile(): Result<ProfileResult> = withContext(Dispatchers.IO) {
        // 1. Ensure Auth
        val initialUser = auth.currentUser ?: return@withContext Result.failure(AppError.Unauthenticated())
        
        try {
            try {
                withTimeout(5.seconds) {
                    initialUser.reload().await()
                }
            } catch (e: Exception) {
                diagnosticLogger.warn(DiagnosticCategory.AUTH, "USER_RELOAD_FAILED", mapOf(LogKeys.USER_ID to initialUser.uid), e)
                
                // CRITICAL FIX: Only sign out if the error clearly indicates the account is invalid/revoked.
                // Network failures or timeouts must NOT trigger a logout, as this would cause permanent
                // local data loss in the LogoutCoordinator.
                if (e is FirebaseAuthInvalidUserException) {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "AUTH_SESSION_REVOKED", mapOf(LogKeys.USER_ID to initialUser.uid))
                    auth.signOut()
                    return@withContext Result.failure(AppError.Unauthenticated("Session revoked: ${e.errorCode}"))
                }
                
                // For all other errors (Network, Timeout, etc.), we proceed using the existing local session.
                // Firebase SDK will handle token refresh retries automatically when connectivity returns.
                diagnosticLogger.info(DiagnosticCategory.AUTH, "RELOAD_SKIPPED_FOR_TRANSIENT_ERROR", mapOf("errorType" to e.javaClass.simpleName))
            }

            val currentUser = auth.currentUser ?: return@withContext Result.failure(AppError.Unauthenticated())
            val userRef = usersCollection.document(currentUser.uid)
            val privateRef = userRef.collection("private").document("settings")

            // 2. Atomic Check & Create via Transaction
            val profileResult = withTimeout(15.seconds) {
                firestore.runTransaction { transaction ->
                    val snapshot = transaction[userRef]
                    
                    if (snapshot.exists()) {
                        diagnosticLogger.debug(DiagnosticCategory.FIRESTORE, "USER_PROFILE_EXISTS", mapOf(LogKeys.USER_ID to currentUser.uid))
                        
                        val user = snapshot.toObject(User::class.java)?.copy(id = snapshot.id)
                            ?: throw IllegalStateException("Failed to deserialize existing User profile")
                        
                        val privateSnapshot = transaction[privateRef]
                        val privateMissing = !privateSnapshot.exists()

                        // PHASE 1: Sensitive Data Migration (Atomic & Idempotent)
                        val sensitiveFields = listOf(
                            "email", "realName", "fcmToken", "isAdmin", "accountStatus", "admin",
                            "emotionPreferences", "lastActivityTimestamp", "softStreakCount", "lastSeen"
                        )
                        val fieldsToMove = mutableMapOf<String, Any>()
                        sensitiveFields.forEach { field ->
                            snapshot.get(field)?.let { value ->
                                fieldsToMove[field] = value
                            }
                        }

                        if (fieldsToMove.isNotEmpty() || privateMissing) {
                            diagnosticLogger.info(DiagnosticCategory.AUTH, "USER_PROFILE_NORMALIZED", mapOf(LogKeys.USER_ID to currentUser.uid))

                            if (fieldsToMove.isNotEmpty()) {
                                // 1. Move fields to private settings (Merge to preserve existing data)
                                transaction.set(privateRef, fieldsToMove, com.google.firebase.firestore.SetOptions.merge())
                                
                                // 2. Remove from root document
                                val deletions = fieldsToMove.keys.associateWith { FieldValue.delete() }
                                transaction.update(userRef, deletions)
                                
                                diagnosticLogger.info(DiagnosticCategory.AUTH, "SENSITIVE_DATA_MIGRATED", mapOf(LogKeys.USER_ID to currentUser.uid, "fields" to fieldsToMove.keys.toList()))
                            }

                            if (privateMissing && fieldsToMove.isEmpty()) {
                                // Standard initialization for new users or missing private doc
                                val defaultPrivate = UserPrivateSettings(
                                    secureEmail = SecureString.fromString(currentUser.email ?: ""),
                                    secureRealName = SecureString.fromString(currentUser.displayName ?: ""),
                                    isAdmin = false,
                                    accountStatus = "ACTIVE"
                                )
                                transaction[privateRef] = defaultPrivate
                            }
                        }

                        // PHASE 2: Identity Repair (Atomic & Idempotent)
                        // Verified Fix for "Zombie Profile" condition where identity fields are missing/blank.
                        val isIdentityIncomplete = user.anonymousId.isBlank() || 
                                                  user.anonymousName.isBlank() || 
                                                  user.anonymousSigil.isBlank() ||
                                                  user.sigilSeed.isBlank()
                        
                        val repairedUser = if (isIdentityIncomplete) {
                            diagnosticLogger.info(DiagnosticCategory.AUTH, "USER_PROFILE_REPAIR_TRIGGERED", mapOf(LogKeys.USER_ID to currentUser.uid))
                            
                            val newAnonId = if (user.anonymousId.isBlank()) "usr_${java.util.UUID.randomUUID().toString().take(5).uppercase()}" else user.anonymousId
                            val newName = if (user.anonymousName.isBlank()) UsernameGenerator.generate() else user.anonymousName
                            val newSigil = if (user.anonymousSigil.isBlank()) UsernameGenerator.deriveSigil(newAnonId) else user.anonymousSigil
                            val newSeed = if (user.sigilSeed.isBlank()) java.util.UUID.randomUUID().toString() else user.sigilSeed
                            
                            val updates = mutableMapOf<String, Any>()
                            if (user.anonymousId.isBlank()) updates["anonymousId"] = newAnonId
                            if (user.anonymousName.isBlank()) updates["anonymousName"] = newName
                            if (user.anonymousSigil.isBlank()) updates["anonymousSigil"] = newSigil
                            if (user.sigilSeed.isBlank()) {
                                updates["sigilSeed"] = newSeed
                                updates["sigilConfig.seed"] = newSeed
                            }
                            
                            if (updates.isNotEmpty()) {
                                transaction.update(userRef, updates)
                                diagnosticLogger.info(DiagnosticCategory.AUTH, "USER_PROFILE_REPAIRED", mapOf(LogKeys.USER_ID to currentUser.uid, "fields" to updates.keys.toList()))
                            }
                            
                            user.copy(
                                anonymousId = newAnonId,
                                anonymousName = newName,
                                anonymousSigil = newSigil,
                                sigilSeed = newSeed,
                                sigilConfig = user.sigilConfig.copy(seed = newSeed)
                            )
                        } else {
                            user
                        }

                        ProfileResult(user = repairedUser, isNewUser = false)
                    } else {
                        val anonymousId = "usr_${java.util.UUID.randomUUID().toString().take(5).uppercase()}"
                        val anonymousName = UsernameGenerator.generate()
                        val anonymousSigil = UsernameGenerator.deriveSigil(anonymousId)
                        val seed = java.util.UUID.randomUUID().toString()
                        
                        val newProfile = User(
                            id = currentUser.uid,
                            anonymousId = anonymousId,
                            anonymousName = anonymousName,
                            anonymousSigil = anonymousSigil,
                            sigilSeed = seed,
                            sigilConfig = SigilConfig(
                                seed = seed,
                                version = 3
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
            
            // Cache the profile locally
            try {
                userDao.get().insertProfile(mapUserToLocal(profileResult.user))
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.DATABASE, "USER_PROFILE_CACHE_FAILED", mapOf(LogKeys.USER_ID to profileResult.user.id), e)
            }

            Result.success(profileResult)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "REGISTRATION_TIMEOUT", throwable = e)
            Result.failure(AppError.from(e))
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "REGISTRATION_FAILED", throwable = e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Fetches the user profile from the local cache.
     * Useful for offline-first scenarios or when network is unavailable.
     */
    suspend fun getCachedProfile(userId: String? = null): User? = withContext(Dispatchers.IO) {
        val targetUserId = userId ?: auth.currentUser?.uid ?: return@withContext null
        return@withContext try {
            userDao.get().getProfile(targetUserId)?.let { mapLocalToUser(it) }
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.DATABASE, "USER_PROFILE_FETCH_CACHED_FAILED", mapOf(LogKeys.USER_ID to targetUserId), e)
            null
        }
    }

    private fun mapUserToLocal(user: User): UserLocalEntity {
        return UserLocalEntity(
            id = user.id,
            anonymousId = user.anonymousId,
            anonymousName = user.anonymousName,
            anonymousSigil = user.anonymousSigil,
            sigilSeed = user.sigilSeed,
            sigilColor = user.sigilColor,
            sigilConfigJson = kotlinx.serialization.json.Json.encodeToString(user.sigilConfig)
        )
    }

    private fun mapLocalToUser(local: UserLocalEntity): User {
        return User(
            id = local.id,
            anonymousId = local.anonymousId,
            anonymousName = local.anonymousName,
            anonymousSigil = local.anonymousSigil,
            sigilSeed = local.sigilSeed,
            sigilColor = local.sigilColor,
            sigilConfig = try {
                kotlinx.serialization.json.Json.decodeFromString(local.sigilConfigJson)
            } catch (_: Exception) {
                SigilConfig(seed = local.sigilSeed)
            }
        )
    }

    fun streamPublicProfile(personaId: String): Flow<User?> = callbackFlow<User?> {
        if (personaId.isBlank()) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val docRef = firestore.collection("profiles").document(personaId.trim())
        val subscription = docRef.addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                val user = User(
                    id = personaId,
                    anonymousId = personaId,
                    anonymousName = snapshot.getString("name") ?: "quiet presence",
                    anonymousSigil = snapshot.getString("sigil") ?: "",
                    sigilSeed = snapshot.getString("sigilSeed") ?: "",
                    sigilColor = snapshot.getString("sigilColor") ?: "#FFD700",
                    artifactsCount = snapshot.getLong("artifactsCount") ?: 0L,
                    resonanceInCount = snapshot.getLong("resonanceInCount") ?: 0L,
                    followersCount = snapshot.getLong("followersCount") ?: 0L
                )
                trySend(user)
            } else {
                trySend(null)
            }
        }
        awaitClose { subscription.remove() }
    }

    fun streamUserProfile(userId: String?): Flow<User?> = callbackFlow<User?> {
        if (userId.isNullOrBlank()) {
            trySend(null)
            close()
            return@callbackFlow
        }

        if (userId.startsWith("usr_")) {
            val docRef = firestore.collection("profiles").document(userId.trim())
            val registration = docRef.addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val user = User(
                        id = userId,
                        anonymousId = userId,
                        anonymousName = snapshot.getString("name") ?: "quiet presence",
                        anonymousSigil = snapshot.getString("sigil") ?: "",
                        sigilSeed = snapshot.getString("sigilSeed") ?: "",
                        sigilColor = snapshot.getString("sigilColor") ?: "#FFD700",
                        artifactsCount = snapshot.getLong("artifactsCount") ?: 0L,
                        resonanceInCount = snapshot.getLong("resonanceInCount") ?: 0L,
                        followersCount = snapshot.getLong("followersCount") ?: 0L
                    )
                    trySend(user)
                } else {
                    trySend(null)
                }
            }
            awaitClose { registration.remove() }
            return@callbackFlow
        }

        val docRef = usersCollection.document(userId.trim())
        val registration = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                trySend(null)
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                try {
                    val user = snapshot.toObject(User::class.java)?.copy(id = snapshot.id)
                    trySend(user)
                } catch (_: Exception) {
                    trySend(null)
                }
            } else {
                trySend(null)
            }
        }
        awaitClose { registration.remove() }
    }.catch { emit(null) }

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
            pendingInteractionDao.get().deleteByType(targetUserId, currentUserId, com.saurabh.artifact.data.local.InteractionType.FOLLOW)
            pendingInteractionDao.get().insert(pending)
            com.saurabh.artifact.worker.InteractionSyncWorker.enqueue(context)
            
            diagnosticLogger.info(DiagnosticCategory.RESONANCE, "FOLLOW_QUEUED", mapOf("targetUserId" to targetUserId))
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RESONANCE, "FOLLOW_QUEUE_FAILED", mapOf("targetUserId" to targetUserId), e)
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
            pendingInteractionDao.get().deleteByType(targetUserId, currentUserId, com.saurabh.artifact.data.local.InteractionType.FOLLOW)
            pendingInteractionDao.get().insert(pending)
            com.saurabh.artifact.worker.InteractionSyncWorker.enqueue(context)
            
            diagnosticLogger.info(DiagnosticCategory.RESONANCE, "UNFOLLOW_QUEUED", mapOf("targetUserId" to targetUserId))
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RESONANCE, "UNFOLLOW_QUEUE_FAILED", mapOf("targetUserId" to targetUserId), e)
            Result.failure(e)
        }
    }

    /**
     * Internal synchronization method for follow.
     * INTERNAL SYNC API: Intended exclusively for InteractionSyncWorker.
     * Performs direct Firestore write without enqueuing.
     */
    internal suspend fun syncFollowToFirestore(currentUserId: String, targetAnonymousId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val intentRef = usersCollection.document(currentUserId)
                .collection("private").document("intents")
                .collection("follow").document(targetAnonymousId)
            
            intentRef.set(mapOf(
                "targetAnonymousId" to targetAnonymousId,
                "action" to "FOLLOW",
                "timestamp" to FieldValue.serverTimestamp(),
                "version" to 1
            )).await()
            
            diagnosticLogger.info(DiagnosticCategory.RESONANCE, "FOLLOW_INTENT_CREATED", mapOf("targetAnonymousId" to targetAnonymousId))
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RESONANCE, "FOLLOW_INTENT_FAILED", mapOf("targetAnonymousId" to targetAnonymousId), e)
            Result.failure(e)
        }
    }

    /**
     * Internal synchronization method for unfollow.
     * INTERNAL SYNC API: Intended exclusively for InteractionSyncWorker.
     * Performs direct Firestore write without enqueuing.
     */
    internal suspend fun syncUnfollowFromFirestore(currentUserId: String, targetAnonymousId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val intentRef = usersCollection.document(currentUserId)
                .collection("private").document("intents")
                .collection("follow").document(targetAnonymousId)
            
            intentRef.delete().await()
            
            diagnosticLogger.info(DiagnosticCategory.RESONANCE, "FOLLOW_INTENT_REMOVED", mapOf("targetAnonymousId" to targetAnonymousId))
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RESONANCE, "FOLLOW_INTENT_REMOVE_FAILED", mapOf("targetAnonymousId" to targetAnonymousId), e)
            Result.failure(e)
        }
    }

    /**
     * Streams the resonance relationship status between two users.
     * Upgraded to be fully reactive across both modern and legacy collections.
     */
    fun observeIsResonating(currentUserId: String, targetPersonaId: String): Flow<Boolean> {
        if (currentUserId.isBlank() || targetPersonaId.isBlank()) {
            return flowOf(value = false)
        }

        val modernRef = usersCollection.document(currentUserId.trim())
            .collection("resonance_out").document(targetPersonaId.trim())

        val legacyRef = usersCollection.document(currentUserId.trim())
            .collection("following").document(targetPersonaId.trim())

        return combine(
            observeDocumentExists(modernRef),
            observeDocumentExists(legacyRef)
        ) { modern, legacy -> modern || legacy }
    }

    fun observeResonatingWithIds(userId: String): Flow<Set<String>> {
        if (userId.isBlank()) return flowOf(emptySet())
        
        val modernRef = usersCollection.document(userId).collection("resonance_out")
        val legacyRef = usersCollection.document(userId).collection("following")
        
        return combine(
            observeCollectionIds(modernRef),
            observeCollectionIds(legacyRef)
        ) { modern, legacy ->
            modern + legacy
        }
    }

    private fun observeCollectionIds(collectionRef: CollectionReference): Flow<Set<String>> = callbackFlow {
        val subscription = collectionRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val ids = snapshot?.documents?.map { it.id }?.toSet() ?: emptySet()
            trySend(ids)
        }
        awaitClose { subscription.remove() }
    }

    private fun observeDocumentExists(docRef: DocumentReference): Flow<Boolean> = callbackFlow {
        diagnosticLogger.info(
            DiagnosticCategory.FIRESTORE,
            "FIRESTORE_LISTENER_REGISTERED",
            mapOf(
                "path" to docRef.path,
                "uid" to (auth.currentUser?.uid ?: "null"),
                "thread" to Thread.currentThread().name,
                "timestamp" to System.currentTimeMillis()
            )
        )

        val registration = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                diagnosticLogger.error(
                    DiagnosticCategory.FIRESTORE,
                    "SNAPSHOT_CALLBACK",
                    mapOf(
                        "path" to docRef.path,
                        "type" to "ERROR",
                        "code" to error.code.name,
                        "message" to (error.message ?: ""),
                        "cause" to (error.cause?.toString() ?: "null"),
                        "timestamp" to System.currentTimeMillis()
                    ),
                    error
                )
                // HARDENING: Do not crash or hang on error (e.g. Permission Denied)
                // Just assume document doesn't exist/isn't accessible
                trySend(element = false)
                return@addSnapshotListener
            }

            diagnosticLogger.info(
                DiagnosticCategory.FIRESTORE,
                "SNAPSHOT_CALLBACK",
                mapOf(
                    "path" to docRef.path,
                    "type" to "SUCCESS",
                    "exists" to (snapshot?.exists() ?: false),
                    "timestamp" to System.currentTimeMillis()
                )
            )

            trySend(element = snapshot?.exists() ?: false)
        }
        awaitClose { 
            diagnosticLogger.info(DiagnosticCategory.FIRESTORE, "LISTENER_TERMINATED", mapOf("path" to docRef.path, "timestamp" to System.currentTimeMillis()))
            registration.remove() 
        }
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

    suspend fun updateSigilConfig(userId: String, config: SigilConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // SELF-HEALING: Ensure profile exists before update
            registrationCoordinator.get().ensureProfileExists()

            val userRef = usersCollection.document(userId)
            val userSnapshot = userRef.get().await()
            val user = userSnapshot.toObject(User::class.java)?.copy(id = userSnapshot.id)
                ?: return@withContext Result.failure(AppError.NotFound("User", userId))
            
            val isWithinWindow = identityProtectionPolicy.isWithinWindow(user.identityMetadata.lastIdentityChangeAt)
            val newCount = if (isWithinWindow) user.identityMetadata.identityChangeCount30Days + 1 else 1

            userRef.update(
                mapOf(
                    "sigilConfig" to config,
                    "usernameUpdatedAt" to FieldValue.serverTimestamp(),
                    "identityMetadata.lastIdentityChangeAt" to FieldValue.serverTimestamp(),
                    "identityMetadata.identityChangeCount30Days" to newCount,
                    "identityMetadata.identityResetVersion" to FieldValue.increment(1) // Trigger backend propagation
                )
            ).await()

            diagnosticLogger.info(DiagnosticCategory.AUTH, "SIGIL_CONFIG_UPDATED", mapOf(LogKeys.USER_ID to userId))
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "SIGIL_CONFIG_UPDATE_FAILED", mapOf(LogKeys.USER_ID to userId), e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Immediately randomizes the user's identity for emergency privacy protection.
     * Hardened with a version-based state machine and atomic transaction.
     *
     * @param severRelationships If true, the backend will reciprocally sever all Follow Journey relationships.
     */
    suspend fun emergencyIdentityReset(userId: String, severRelationships: Boolean = false): Result<Long> = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext Result.failure(AppError.InvalidInput("User ID cannot be blank"))

        try {
            val userRef = usersCollection.document(userId)
            
            val (newName, newVersion) = firestore.runTransaction { transaction ->
                val userSnapshot = transaction[userRef]
                val user = userSnapshot.toObject(User::class.java)?.copy(id = userSnapshot.id)
                    ?: throw IllegalStateException("User profile not found")

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

                // 3. Update user profile (Sanitization: Refresh anonymousId for true anonymity)
                val newAnonId = "usr_${java.util.UUID.randomUUID().toString().take(5).uppercase()}"
                
                val updateMap = mutableMapOf<String, Any>(
                    "anonymousId" to newAnonId,
                    "anonymousName" to generatedName,
                    "anonymousSigil" to UsernameGenerator.deriveSigil(newAnonId),
                    "sigilSeed" to java.util.UUID.randomUUID().toString(),
                    "sigilConfig.seed" to java.util.UUID.randomUUID().toString(),
                    "usernameUpdatedAt" to FieldValue.serverTimestamp(),
                    "identityMetadata.lastIdentityChangeAt" to FieldValue.serverTimestamp(),
                    "identityMetadata.emergencyResetCount" to FieldValue.increment(1),
                    "identityMetadata.identityResetVersion" to nextVersion,
                    "identityMetadata.resetStartedAt" to FieldValue.serverTimestamp()
                )
                
                if (severRelationships) {
                    updateMap["identityMetadata.severRelationships"] = true
                }
                
                transaction.update(userRef, updateMap)

                generatedName to nextVersion
            }.await()

            // 4. Update local profile cache (Isolated/Optimistic)
            try {
                getCachedProfile()?.let { user ->
                    val newSeed = java.util.UUID.randomUUID().toString()
                    val updatedUser = user.copy(
                        anonymousName = newName,
                        sigilSeed = newSeed,
                        sigilConfig = user.sigilConfig.copy(seed = newSeed),
                        identityMetadata = user.identityMetadata.copy(
                            emergencyResetCount = user.identityMetadata.emergencyResetCount + 1,
                            identityResetVersion = newVersion,
                            resetStartedAt = com.google.firebase.Timestamp.now()
                        )
                    )
                    userDao.get().insertProfile(mapUserToLocal(updatedUser))
                }
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.DATABASE, "EMERGENCY_RESET_CACHE_FAILED", mapOf(LogKeys.USER_ID to userId), e)
            }

            // Zero-Trust: Notification handled by backend (optional/future)
            // notificationRepository.createNotification(
            //     userId = userId,
            //     message = "IDENTITY_PROTECTED|$newName"
            // )

            // Log moderation event (Isolated)
            try {
                val reportRef = firestore.collection("reports").document()
                reportRef.set(mapOf(
                    "type" to "EMERGENCY_RESET",
                    "userId" to userId,
                    "reporterId" to userId,
                    "timestamp" to FieldValue.serverTimestamp(),
                    "reason" to "USER_TRIGGERED_PRIVACY_PROTECTION",
                    "version" to newVersion
                )).await()
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "EMERGENCY_RESET_AUDIT_FAILED", mapOf(LogKeys.USER_ID to userId), e)
            }

            // Trigger global identity synchronization (supersede any existing propagation)
            IdentitySyncWorker.enqueue(context, userId, newVersion, androidx.work.ExistingWorkPolicy.REPLACE)

            diagnosticLogger.info(DiagnosticCategory.AUTH, "EMERGENCY_RESET_SUCCESS", mapOf(LogKeys.USER_ID to userId, "version" to newVersion))
            Result.success(newVersion)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "EMERGENCY_RESET_FAILED", mapOf(LogKeys.USER_ID to userId), e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Reports an identity exposure (doxxing) incident.
     */
    suspend fun reportIdentityExposure(
        reporterId: String,
        reportedUserId: String,
        artifactId: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val reportRef = firestore.collection("reports").document()
            reportRef.set(mapOf(
                "type" to "IDENTITY_EXPOSURE",
                "priority" to "CRITICAL",
                "reporterId" to reporterId,
                "reportedUserId" to reportedUserId,
                "artifactId" to artifactId,
                "timestamp" to FieldValue.serverTimestamp(),
                "status" to "PENDING"
            )).await()

            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "IDENTITY_EXPOSURE_REPORT_FAILED", mapOf("reporterId" to reporterId, "reportedUserId" to reportedUserId), e)
            Result.failure(e)
        }
    }

    /**
     * Fetches a paginated list of users who are either "resonators" (resonance_in)
     * or being "resonated with" (resonance_out) by the target user.
     * Uses anonymous personas for public decoupling.
     */
    suspend fun getResonanceUsers(
        userId: String, // This may be UID (self) or anonymousId (others)
        type: String, // "resonance_in" or "resonance_out"
        limit: Int = 20,
        lastVisible: DocumentSnapshot? = null
    ): Result<Pair<List<User>, DocumentSnapshot?>> {
        return withContext(Dispatchers.IO) {
            try {
                // If it's resonance_out for self, we use UID.
                // If it's resonance_in for anyone, or resonance_out for others, we are effectively 
                // browsing a persona's social graph.
                
                // For simplicity in this remediation, we assume 'userId' is the document path pivot.
                val rootRef = if (userId.startsWith("usr_")) {
                    firestore.collection("profiles").document(userId)
                } else {
                    usersCollection.document(userId)
                }

                var query = rootRef.collection(type)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(limit.toLong())

                lastVisible?.let { query = query.startAfter(it) }

                val snapshot = query.get().await()
                if (snapshot.isEmpty) return@withContext Result.success(emptyList<User>() to null)

                // The IDs in resonance collection are now anonymousIds
                val personaIds = snapshot.documents.map { it.id }
                
                // Batch fetch from 'profiles'
                val users = mutableListOf<User>()
                for (chunk in personaIds.chunked(10)) {
                    val profileSnapshot = firestore.collection("profiles")
                        .whereIn(FieldPath.documentId(), chunk).get().await()
                    
                    users.addAll(profileSnapshot.documents.mapNotNull { doc ->
                        User(
                            id = doc.id,
                            anonymousId = doc.id,
                            anonymousName = doc.getString("name") ?: "quiet presence",
                            anonymousSigil = doc.getString("sigil") ?: "",
                            sigilSeed = doc.getString("sigilSeed") ?: "",
                            sigilColor = doc.getString("sigilColor") ?: "#FFD700",
                            artifactsCount = doc.getLong("artifactsCount") ?: 0L,
                            resonanceInCount = doc.getLong("resonanceInCount") ?: 0L,
                            followersCount = doc.getLong("followersCount") ?: 0L
                        )
                    })
                }

                // Maintain original order
                val orderedUsers = personaIds.mapNotNull { id -> users.find { it.anonymousId == id } }

                Result.success(orderedUsers to snapshot.documents.lastOrNull())
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "RESONANCE_USERS_FETCH_FAILED", mapOf(LogKeys.USER_ID to userId, "type" to type), e)
                Result.failure(e)
            }
        }
    }

    /**
     * Fetches a paginated list of users who resonated with a specific artifact.
     * Sanitized: Maps authorAnonymousId to public profile documents.
     */
    suspend fun getArtifactResonators(
        artifactId: String,
        isOwner: Boolean = false,
        limit: Int = 20,
        lastVisible: DocumentSnapshot? = null
    ): Result<Pair<List<User>, DocumentSnapshot?>> {
        return withContext(Dispatchers.IO) {
            try {
                var query = firestore.collection("artifact_reactions")
                    .whereEqualTo("artifactId", artifactId)

                // OWNER PATH: If the user is the owner, add the proving filter to satisfy security rules.
                if (isOwner) {
                    val currentUserId = getCurrentUserId()
                    if (currentUserId != null) {
                        query = query.whereEqualTo("artifactOwnerId", currentUserId)
                    }
                }

                query = query.orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(limit.toLong())

                lastVisible?.let { query = query.startAfter(it) }

                val snapshot = query.get().await()
                if (snapshot.isEmpty) return@withContext Result.success(emptyList<User>() to null)

                // Extract unique anonymousIds
                val personaIds = snapshot.documents.mapNotNull { it.getString("authorAnonymousId") }.distinct()
                
                if (personaIds.isEmpty()) {
                    return@withContext Result.success(emptyList<User>() to snapshot.documents.lastOrNull())
                }

                // Batch fetch from 'profiles'
                val users = mutableListOf<User>()
                for (chunk in personaIds.chunked(10)) {
                    val profileSnapshot = firestore.collection("profiles")
                        .whereIn(FieldPath.documentId(), chunk).get().await()
                    
                    users.addAll(profileSnapshot.documents.mapNotNull { doc ->
                        User(
                            id = doc.id,
                            anonymousId = doc.id,
                            anonymousName = doc.getString("name") ?: "quiet presence",
                            anonymousSigil = doc.getString("sigil") ?: "",
                            sigilSeed = doc.getString("sigilSeed") ?: "",
                            sigilColor = doc.getString("sigilColor") ?: "#FFD700",
                            artifactsCount = doc.getLong("artifactsCount") ?: 0L,
                            resonanceInCount = doc.getLong("resonanceInCount") ?: 0L,
                            followersCount = doc.getLong("followersCount") ?: 0L
                        )
                    })
                }

                // Maintain original order
                val orderedUsers = personaIds.mapNotNull { id -> users.find { it.anonymousId == id } }

                Result.success(orderedUsers to snapshot.documents.lastOrNull())
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "ARTIFACT_RESONATORS_FETCH_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), e)
                Result.failure(e)
            }
        }
    }

    /**
     * Enqueues an artifact count increment operation to be processed asynchronously.
     */
    suspend fun enqueueArtifactCountIncrement(userId: String, artifactId: String) {
        val interaction = com.saurabh.artifact.data.local.PendingInteractionEntity(
            userId = userId,
            artifactId = artifactId,
            interactionType = com.saurabh.artifact.data.local.InteractionType.ARTIFACT_COUNT,
            action = com.saurabh.artifact.data.local.InteractionAction.ADD
        )
        pendingInteractionDao.get().insert(interaction)
        com.saurabh.artifact.worker.InteractionSyncWorker.enqueue(context)
    }

    /**
     * Enqueues an artifact count decrement operation to be processed asynchronously.
     * Hardened against duplicates to prevent double-decrementing user profile metadata.
     */
    suspend fun enqueueArtifactCountDecrement(userId: String, artifactId: String) {
        val type = com.saurabh.artifact.data.local.InteractionType.ARTIFACT_COUNT
        val action = com.saurabh.artifact.data.local.InteractionAction.REMOVE

        // Concurrency Guard: Check for existing pending decrement for this specific artifact
        val existing = pendingInteractionDao.get().getPendingByType(artifactId, userId, type)
        if (existing.any { it.action == action }) {
            diagnosticLogger.info(
                DiagnosticCategory.SYNC,
                "DECREMENT_ENQUEUE_SKIPPED_DUPLICATE",
                mapOf(LogKeys.ARTIFACT_ID to artifactId)
            )
            return
        }

        val interaction = com.saurabh.artifact.data.local.PendingInteractionEntity(
            userId = userId,
            artifactId = artifactId,
            interactionType = type,
            action = action
        )
        pendingInteractionDao.get().insert(interaction)
        com.saurabh.artifact.worker.InteractionSyncWorker.enqueue(context)
    }

    /**
     * Increments the artifact count for a user idempotently.
     * Uses a marker document in users/{userId}/counters/{artifactId} to ensure
     * that a retry of the same operation doesn't increment twice.
     */
    suspend fun incrementArtifactsCount(userId: String, artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            firestore.runTransaction { transaction ->
                val userRef = usersCollection.document(userId)
                val counterRef = userRef.collection("counters").document(artifactId)
                
                val counterSnapshot = transaction.get(counterRef)
                val isCounted = counterSnapshot.getBoolean("counted") ?: false
                
                if (!isCounted) {
                    transaction.update(userRef, "artifactsCount", FieldValue.increment(1))
                    transaction.set(counterRef, mapOf("counted" to true, "updatedAt" to FieldValue.serverTimestamp()))
                }
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "INCREMENT_ARTIFACTS_COUNT_FAILED", mapOf(LogKeys.USER_ID to userId, LogKeys.ARTIFACT_ID to artifactId), e)
            Result.failure(e)
        }
    }

    /**
     * Decrements the artifact count for a user idempotently.
     */
    suspend fun decrementArtifactsCount(userId: String, artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            firestore.runTransaction { transaction ->
                val userRef = usersCollection.document(userId)
                val counterRef = userRef.collection("counters").document(artifactId)
                
                val counterSnapshot = transaction.get(counterRef)
                val isCounted = counterSnapshot.getBoolean("counted") ?: false
                
                if (isCounted) {
                    transaction.update(userRef, "artifactsCount", FieldValue.increment(-1))
                    // We delete the marker to allow re-increment if ever published again (unlikely but safe)
                    // or just set to false. Deleting is cleaner for storage.
                    transaction.delete(counterRef)
                }
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "DECREMENT_ARTIFACTS_COUNT_FAILED", mapOf(LogKeys.USER_ID to userId, LogKeys.ARTIFACT_ID to artifactId), e)
            Result.failure(e)
        }
    }

    /**
     * Privately ignores a Presence (User).
     * Synchronizes to Firestore private collection and local Room cache.
     */
    suspend fun ignoreUser(targetUserId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val currentUserId = getCurrentUserId() ?: return@withContext Result.failure(AppError.Unauthenticated())
        if (currentUserId == targetUserId) return@withContext Result.failure(AppError.InvalidInput("Cannot ignore yourself"))

        try {
            // 1. Remote Write
            usersCollection.document(currentUserId)
                .collection("private").document("ignored_users")
                .collection("users").document(targetUserId)
                .set(mapOf("ignoredAt" to FieldValue.serverTimestamp()))
                .await()

            // 2. Local Write
            ignoredUserDao.get().insert(com.saurabh.artifact.data.local.IgnoredUserEntity(targetUserId))
            
            diagnosticLogger.info(DiagnosticCategory.RESONANCE, "USER_IGNORED", mapOf("targetUserId" to targetUserId))
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RESONANCE, "USER_IGNORE_FAILED", mapOf("targetUserId" to targetUserId), e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Removes an ignore relationship privately.
     */
    suspend fun unignoreUser(targetUserId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val currentUserId = getCurrentUserId() ?: return@withContext Result.failure(AppError.Unauthenticated())

        try {
            // 1. Remote Delete
            usersCollection.document(currentUserId)
                .collection("private").document("ignored_users")
                .collection("users").document(targetUserId)
                .delete()
                .await()

            // 2. Local Delete
            ignoredUserDao.get().delete(targetUserId)
            
            diagnosticLogger.info(DiagnosticCategory.RESONANCE, "USER_UNIGNORED", mapOf("targetUserId" to targetUserId))
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RESONANCE, "USER_UNIGNORE_FAILED", mapOf("targetUserId" to targetUserId), e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Synchronizes the user's private ignore list from Firestore to Room.
     */
    suspend fun syncIgnoredUsers(): Result<Unit> = withContext(Dispatchers.IO) {
        val userId = getCurrentUserId() ?: return@withContext Result.failure(AppError.Unauthenticated())

        try {
            val snapshot = usersCollection.document(userId)
                .collection("private").document("ignored_users")
                .collection("users")
                .get()
                .await()

            val ignoredUserIds = snapshot.documents.map { it.id }
            
            // Atomic Refresh
            val dao = ignoredUserDao.get()
            dao.deleteAll()
            ignoredUserIds.forEach { targetId ->
                dao.insert(com.saurabh.artifact.data.local.IgnoredUserEntity(targetId))
            }

            diagnosticLogger.info(DiagnosticCategory.RESONANCE, "IGNORED_USERS_SYNCED", mapOf("count" to ignoredUserIds.size))
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RESONANCE, "IGNORED_USERS_SYNC_FAILED", throwable = e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Streams the set of ignored user IDs for the current user.
     */
    fun observeIgnoredUsers(): Flow<Set<String>> {
        return ignoredUserDao.get().observeAllIgnoredUserIds().map { it.toSet() }
    }
}
