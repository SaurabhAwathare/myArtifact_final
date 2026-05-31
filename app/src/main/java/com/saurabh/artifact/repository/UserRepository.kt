package com.saurabh.artifact.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.User
import com.saurabh.artifact.util.UsernameGenerator
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.google.firebase.firestore.FirebaseFirestoreException

@Singleton
class UserRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val notificationRepository: NotificationRepository
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
            
            val safePalette = listOf("#FADADD", "#E6E6FA", "#D1EAF0", "#E2F0D9", "#FFF4E0")
            val newColor = safePalette.random()
            
            firestore.runTransaction { transaction ->
                // 1. Audit Log: Store the transition history
                val historyRef = userRef.collection("private").document("identity_history")
                    .collection("log").document()
                
                transaction.set(historyRef, mapOf(
                    "oldName" to (anonymousSnapshot?.anonymousName ?: "Unknown"),
                    "newName" to newName,
                    "oldSigil" to (anonymousSnapshot?.anonymousSigil ?: ""),
                    "newSigil" to newSigil,
                    "timestamp" to FieldValue.serverTimestamp(),
                    "reason" to "USER_REFRESH"
                ))

                // 2. Update Profile
                transaction.update(
                    userRef, mapOf(
                        "anonymousName" to newName,
                        "anonymousSigil" to newSigil,
                        "avatarColor" to newColor,
                        "avatarSeed" to java.util.UUID.randomUUID().toString(),
                        "usernameUpdatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
            
            notificationRepository.createNotification(
                userId = userId,
                message = "You have shed your old presence and emerged as $newName · $newSigil 🎭"
            )

            Result.success(getOrCreateProfile())
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
                val usernameDoc = transaction.get(usernameRef)
                if (usernameDoc.exists()) {
                    val existingUserId = usernameDoc.getString("uid") ?: usernameDoc.getString("userId")
                    if (existingUserId != userId) {
                        throw AppError.UsernameTaken(normalizedUsername)
                    }
                }

                // 2. Get current user to find old username for cleanup
                val userDoc = transaction.get(userRef)
                val oldUsername = userDoc.getString("anonymousName")?.lowercase()?.trim()

                // 3. Reserve the new username
                transaction.set(usernameRef, mapOf(
                    "uid" to userId,
                    "createdAt" to FieldValue.serverTimestamp()
                ))

                // 4. Update the user profile
                transaction.update(userRef, mapOf(
                    "anonymousName" to username, // This is the chosen "pseudonym"
                    "isAnonymous" to false, // They've chosen a name, though still "artifact" anonymous
                    "usernameUpdatedAt" to FieldValue.serverTimestamp()
                ))

                // 5. Clean up old username reservation
                if (oldUsername != null && oldUsername != normalizedUsername) {
                    transaction.delete(usernamesCollection.document(oldUsername))
                }
            }.await()

            notificationRepository.createNotification(
                userId = userId,
                message = "You updated your username to $username ✨"
            )

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
    suspend fun isUsernameAvailable(username: String): Boolean = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext true
        try {
            val doc = usernamesCollection.document(username.lowercase().trim()).get().await()
            !doc.exists()
        } catch (e: Exception) {
            Log.e("UserRepository", "Error checking username availability", e)
            // Default to false on error to be safe, or true if we want to allow retry on submit
            false
        }
    }

    /**
     * Checks if a user profile document exists in Firestore.
     * Used for first-time user detection and routing logic.
     */
    suspend fun isProfileCreated(userId: String): Boolean = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext false
        try {
            val doc = usersCollection.document(userId.trim()).get().await()
            doc.exists()
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Error checking profile existence", e)
            false
        }
    }

    suspend fun getOrCreateProfile(): User {
        // 1. Ensure Auth
        val initialUser = auth.currentUser ?: throw IllegalStateException("Firebase Auth failed to provide a valid user.")
        
        try {
            initialUser.reload().await()
        } catch (e: Exception) {
            android.util.Log.w("UserRepository", "Failed to reload user, might be deleted or session expired.", e)
            auth.signOut()
            throw e
        }

        val currentUser = auth.currentUser ?: throw IllegalStateException("Firebase Auth failed to provide a valid user after reload.")
        val userRef = usersCollection.document(currentUser.uid)
        val privateRef = userRef.collection("private").document("settings")

        // 2. Atomic Check & Create via Transaction
        return firestore.runTransaction { transaction ->
            val snapshot = transaction.get(userRef)
            
            if (snapshot.exists()) {
                // Safe deserialization
                snapshot.toObject(User::class.java)?.copy(id = currentUser.uid)
                    ?: throw IllegalStateException("User document exists but is malformed.")
            } else {
                // Initialize new fully anonymous profile
                val anonymousId = "usr_${java.util.UUID.randomUUID().toString().take(5).uppercase()}"
                val anonymousName = UsernameGenerator.generate()
                val anonymousSigil = UsernameGenerator.deriveSigil(anonymousId)
                val safePalette = listOf("#FADADD", "#E6E6FA", "#D1EAF0", "#E2F0D9", "#FFF4E0")
                
                val newProfile = User(
                    id = currentUser.uid,
                    anonymousId = anonymousId,
                    anonymousName = anonymousName,
                    anonymousSigil = anonymousSigil,
                    avatarSeed = safePalette.random(),
                    isAnonymous = true,
                    emotionalProfile = "New Soul"
                )

                val privateSettings = com.saurabh.artifact.model.UserPrivateSettings(
                    email = currentUser.email ?: "",
                    realName = currentUser.displayName ?: "",
                    isAdmin = false,
                    accountStatus = "ACTIVE"
                )

                transaction.set(userRef, newProfile)
                transaction.set(privateRef, privateSettings)
                newProfile
            }
        }.await()
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
                // If it's a permanent error (Permission Denied), we emit null and close the flow.
                if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    trySend(null)
                    close(error)
                }
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
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

                val resonanceDoc = transaction.get(resonanceOutRef)
                if (resonanceDoc.exists()) return@runTransaction // Already resonating

                // 1. Create relationship markers
                val timestamp = FieldValue.serverTimestamp()
                transaction.set(resonanceOutRef, mapOf("createdAt" to timestamp))
                transaction.set(resonanceInRef, mapOf("createdAt" to timestamp))

                // 2. Increment counters
                transaction.update(currentUserRef, "resonanceOutCount", FieldValue.increment(1))
                transaction.update(targetUserRef, "resonanceInCount", FieldValue.increment(1))
                
                // Legacy support
                transaction.update(currentUserRef, "followingCount", FieldValue.increment(1))
                transaction.update(targetUserRef, "followersCount", FieldValue.increment(1))
            }.await()
            
            notificationRepository.createNotification(
                userId = targetUserId,
                message = "Someone's presence resonated with your artifacts ✨"
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

                val resonanceDoc = transaction.get(resonanceOutRef)
                if (!resonanceDoc.exists()) return@runTransaction // Not resonating

                // 1. Remove relationship markers
                transaction.delete(resonanceOutRef)
                transaction.delete(resonanceInRef)

                // 2. Decrement counters (safely)
                val currentUserDoc = transaction.get(currentUserRef)
                val targetUserDoc = transaction.get(targetUserRef)

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
     */
    fun observeIsResonating(currentUserId: String, targetUserId: String): Flow<Boolean> = callbackFlow {
        if (currentUserId.isBlank() || targetUserId.isBlank()) {
            trySend(false)
            close()
            return@callbackFlow
        }

        // Check both collections for migration safety
        val docRef = usersCollection.document(currentUserId.trim())
            .collection("resonance_out").document(targetUserId.trim())

        val registration = docRef.addSnapshotListener { snapshot, _ ->
            if (snapshot?.exists() == true) {
                trySend(true)
            } else {
                // Fallback to legacy check
                usersCollection.document(currentUserId.trim())
                    .collection("following").document(targetUserId.trim())
                    .get().addOnSuccessListener { legacySnapshot ->
                        trySend(legacySnapshot.exists())
                    }
            }
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

    suspend fun updateAvatarConfig(userId: String, config: com.saurabh.artifact.model.AvatarConfig) = withContext(Dispatchers.IO) {
        usersCollection.document(userId).update("avatarConfig", config).await()
        notificationRepository.createNotification(
            userId = userId,
            message = "You updated your avatar 🎨"
        )
    }
}
