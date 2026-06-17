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
import com.saurabh.artifact.model.UserPrivateSettings
import com.saurabh.artifact.util.SecureString
import com.saurabh.artifact.util.UsernameGenerator
import com.saurabh.artifact.data.local.UserDao
import com.saurabh.artifact.data.local.UserLocalEntity
import android.util.Log
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
import javax.inject.Inject
import javax.inject.Singleton
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlin.time.Duration.Companion.seconds

@Singleton
class UserRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val notificationRepository: NotificationRepository,
    private val userDao: UserDao,
) {
    private val usersCollection = firestore.collection("users")
    private val usernamesCollection = firestore.collection("usernames")

    /**
     * Refreshes the user's anonymous identity with a new system-generated one.
     * Custom usernames are disabled to prevent doxxing and maintain emotional safety.
     * Enforces a 30-day cooldown to prevent identity churn and maintain community stability.
     */
    suspend fun refreshAnonymousIdentity(userId: String): Result<User> {
        if (userId.isBlank()) {
            return Result.failure(AppError.InvalidInput("User ID cannot be blank"))
        }
        return try {
            val userRef = try {
                usersCollection.document(userId.trim())
            } catch (e: Exception) {
                return Result.failure(AppError.from(e))
            }

            val userSnapshot = userRef.get().await()
            val lastUpdate = userSnapshot.getTimestamp("usernameUpdatedAt")
            
            if (lastUpdate != null) {
                val cooldownMillis = 30L * 24 * 60 * 60 * 1000 // 30 days
                val nextUpdateAllowed = lastUpdate.toDate().time + cooldownMillis
                if (System.currentTimeMillis() < nextUpdateAllowed) {
                    val daysLeft = ((nextUpdateAllowed - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
                    return Result.failure(Exception("Your presence needs more time to settle. You can refresh again in $daysLeft days."))
                }
            }

            val anonymousSnapshot = userSnapshot.toObject(User::class.java)
            val anonymousId = anonymousSnapshot?.anonymousId ?: ""
            val newName = UsernameGenerator.generate()
            val newSigil = UsernameGenerator.deriveSigil(anonymousId)
            val newSeed = java.util.UUID.randomUUID().toString()
            
            val safePalette = listOf("#FADADD", "#E6E6FA", "#D1EAF0", "#E2F0D9", "#FFF4E0")
            val newColor = safePalette.random()
            
            firestore.runTransaction { transaction ->
                // 1. Audit Log: Store the transition history
                val historyRef = userRef.collection("private").document("identity_history")
                    .collection("log").document()
                
                transaction[historyRef] = mapOf(
                    "oldName" to (anonymousSnapshot?.anonymousName ?: "Unknown"),
                    "newName" to newName,
                    "oldSigil" to (anonymousSnapshot?.anonymousSigil ?: ""),
                    "newSigil" to newSigil,
                    "timestamp" to FieldValue.serverTimestamp(),
                    "reason" to "USER_REFRESH",
                )

                // 2. Update Profile
                transaction.update(
                    userRef, mapOf(
                        "anonymousName" to newName,
                        "anonymousSigil" to newSigil,
                        "avatarColor" to newColor,
                        "avatarSeed" to newSeed,
                        "avatarConfig" to (anonymousSnapshot?.avatarConfig ?: AvatarConfig()).copy(
                            seed = newSeed,
                            theme = "AURIC", // Refreshing identity always reverts to brand-standard Aura
                        ),
                        "usernameUpdatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
            
            notificationRepository.createNotification(
                userId = userId,
                message = "IDENTITY_REFRESHED|$newName|$newSigil" // UI layer will map this
            )

            val finalProfileResult = getOrCreateProfile()
            finalProfileResult.onSuccess { profile ->
                try {
                    userDao.insertProfile(mapUserToLocal(profile))
                } catch (e: Exception) {
                    Log.e("UserRepository", "Failed to update cached identity after refresh", e)
                }
            }
            finalProfileResult
        } catch (e: Exception) {
            Log.e("UserRepository", "refreshAnonymousIdentity failed for $userId", e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Creates or updates a unique username for the user.
     * Uses a transaction to ensure uniqueness across the platform.
     */
    suspend fun createUsername(userId: String, username: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext Result.failure(AppError.InvalidInput("User ID cannot be blank"))
        val normalizedUsername = username.lowercase().trim()
        try {
            val userRef = try {
                usersCollection.document(userId.trim())
            } catch (e: Exception) {
                return@withContext Result.failure(AppError.from(e))
            }

            val userSnapshot = userRef.get().await()
            val lastUpdate = userSnapshot.getTimestamp("usernameUpdatedAt")
            
            if (lastUpdate != null) {
                val cooldownMillis = 30L * 24 * 60 * 60 * 1000 // 30 days
                val nextUpdateAllowed = lastUpdate.toDate().time + cooldownMillis
                if (System.currentTimeMillis() < nextUpdateAllowed) {
                    val daysLeft = ((nextUpdateAllowed - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
                    return@withContext Result.failure(Exception("Your presence needs more time to settle. You can refresh again in $daysLeft days."))
                }
            }

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
                        "anonymousName" to username, // This is the chosen "pseudonym"
                    "isAnonymous" to false, // They've chosen a name, though still "artifact" anonymous
                    "usernameUpdatedAt" to FieldValue.serverTimestamp()
                ))

                // 5. Clean up old username reservation
                if ((oldUsername != null) && (oldUsername != normalizedUsername)) {
                    transaction.delete(usernamesCollection.document(oldUsername))
                }
            }.await()

            notificationRepository.createNotification(
                userId = userId,
                message = "USERNAME_UPDATED|$username"
            )

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

    suspend fun getOrCreateProfile(): Result<User> = withContext(Dispatchers.IO) {
        // 1. Ensure Auth
        val initialUser = auth.currentUser ?: return@withContext Result.failure(AppError.Unauthenticated())
        
        try {
            try {
                withTimeout(10.seconds) {
                    initialUser.reload().await()
                }
            } catch (e: Exception) {
                Log.w("UserRepository", "Failed to reload user or timeout reached, might be deleted or session expired.", e)
                // If it's just a timeout, we proceed carefully; if it's a hard error, we sign out.
                if (e !is kotlinx.coroutines.TimeoutCancellationException) {
                    auth.signOut()
                    return@withContext Result.failure(AppError.from(e))
                }
            }

            val currentUser = auth.currentUser ?: return@withContext Result.failure(AppError.Unauthenticated())
            val userRef = usersCollection.document(currentUser.uid)
            val privateRef = userRef.collection("private").document("settings")

            // 2. Atomic Check & Create via Transaction
            val profile = firestore.runTransaction { transaction ->
                val snapshot = transaction[userRef]
                
                if (snapshot.exists()) {
                    // Safe deserialization
                    snapshot.toObject(User::class.java)?.copy(id = currentUser.uid)
                        ?: throw IllegalStateException("User document exists but is malformed.")
                } else {
                    // Initialize new fully anonymous profile
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
                        avatarConfig = AvatarConfig(seed = seed, theme = "AURIC"),
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
                    newProfile
                }
            }.await()
            
            // Cache the profile locally
            try {
                userDao.insertProfile(mapUserToLocal(profile))
            } catch (e: Exception) {
                Log.e("UserRepository", "Failed to cache user profile locally", e)
            }

            Result.success(profile)
        } catch (e: Exception) {
            Log.e("UserRepository", "getOrCreateProfile failed", e)
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
                    val user = snapshot.toObject(User::class.java)?.copy(id = userId)
                    if (user == null) {
                        Log.e("UserRepository", "Stream error: Document exists but deserialization failed for $userId")
                    }
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
     */
    suspend fun resonateWithUser(currentUserId: String, targetUserId: String): Result<Unit> {
        if (currentUserId.isBlank() || targetUserId.isBlank()) {
            return Result.failure(AppError.InvalidInput("User IDs cannot be blank"))
        }
        if (currentUserId == targetUserId) return Result.failure(Exception("Cannot resonate with yourself"))

        return try {
            val currentUserRef = usersCollection.document(currentUserId.trim())
            val targetUserRef = usersCollection.document(targetUserId.trim())

            firestore.runTransaction { transaction ->
                val resonanceOutRef = currentUserRef.collection("resonance_out").document(targetUserId.trim())
                val resonanceInRef = targetUserRef.collection("resonance_in").document(currentUserId.trim())

                val resonanceDoc = transaction[resonanceOutRef]
                if (resonanceDoc.exists()) return@runTransaction // Already resonating

                // 1. Create relationship markers
                val timestamp = FieldValue.serverTimestamp()
                transaction[resonanceOutRef] = mapOf("createdAt" to timestamp)
                transaction[resonanceInRef] = mapOf("createdAt" to timestamp)

                // 2. Increment counters
                transaction.update(currentUserRef, "resonanceOutCount", FieldValue.increment(1))
                transaction.update(targetUserRef, "resonanceInCount", FieldValue.increment(1))
                
                // Legacy support
                transaction.update(currentUserRef, "followingCount", FieldValue.increment(1))
                transaction.update(targetUserRef, "followersCount", FieldValue.increment(1))
            }.await()
            
            notificationRepository.createNotification(
                userId = targetUserId,
                message = "PRESENCE_RESONATED"
            )
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Removes a resonance relationship between two presences atomically.
     */
    suspend fun stopResonatingWithUser(currentUserId: String, targetUserId: String): Result<Unit> {
        if (currentUserId.isBlank() || targetUserId.isBlank()) {
            return Result.failure(AppError.InvalidInput("User IDs cannot be blank"))
        }
        return try {
            val currentUserRef = usersCollection.document(currentUserId.trim())
            val targetUserRef = usersCollection.document(targetUserId.trim())

            firestore.runTransaction { transaction ->
                val resonanceOutRef = currentUserRef.collection("resonance_out").document(targetUserId.trim())
                val resonanceInRef = targetUserRef.collection("resonance_in").document(currentUserId.trim())

                val resonanceDoc = transaction[resonanceOutRef]
                if (!resonanceDoc.exists()) return@runTransaction // Not resonating

                // 1. Remove relationship markers
                transaction.delete(resonanceOutRef)
                transaction.delete(resonanceInRef)

                // 2. Decrement counters (safely)
                val currentUserDoc = transaction[currentUserRef]
                val targetUserDoc = transaction[targetUserRef]

                val outCount = currentUserDoc.getLong("resonanceOutCount") ?: currentUserDoc.getLong("followingCount") ?: 0L
                val inCount = targetUserDoc.getLong("resonanceInCount") ?: targetUserDoc.getLong("followersCount") ?: 0L

                transaction.update(currentUserRef, "resonanceOutCount", (outCount - 1).coerceAtLeast(0))
                transaction.update(targetUserRef, "resonanceInCount", (inCount - 1).coerceAtLeast(0))
                
                // Legacy support
                transaction.update(currentUserRef, "followingCount", (outCount - 1).coerceAtLeast(0))
                transaction.update(targetUserRef, "followersCount", (inCount - 1).coerceAtLeast(0))
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
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
            usersCollection.document(userId).update("avatarConfig", config).await()
            notificationRepository.createNotification(
                userId = userId,
                message = "AVATAR_UPDATED"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("UserRepository", "updateAvatarConfig failed", e)
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
                        userSnapshot.toObjects(User::class.java).mapIndexed { index, user ->
                            user.copy(id = userSnapshot.documents[index].id)
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
}
