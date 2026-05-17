package com.saurabh.artifact.repository

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.model.User
import com.saurabh.artifact.util.NameGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) {
    private val usersCollection = firestore.collection("users")
    private val usernamesCollection = firestore.collection("usernames")

    /**
     * Refreshes the user's anonymous identity with a new system-generated one.
     * Custom usernames are disabled to prevent doxxing and maintain emotional safety.
     */
    suspend fun refreshAnonymousIdentity(userId: String): Result<User> {
        return try {
            val newName = NameGenerator.generate()
            val safePalette = listOf("#FADADD", "#E6E6FA", "#D1EAF0", "#E2F0D9", "#FFF4E0")
            val newColor = safePalette.random()
            
            firestore.runTransaction { transaction ->
                val userRef = usersCollection.document(userId)
                
                transaction.update(
                    userRef, mapOf(
                        "anonymousName" to newName,
                        "displayName" to newName,
                        "avatarColor" to newColor,
                        "usernameUpdatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
            
            Result.success(getOrCreateProfile())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Creates or updates a unique username for the user.
     * Uses a transaction to ensure uniqueness across the platform.
     */
    suspend fun createUsername(userId: String, username: String): Result<Unit> = withContext(Dispatchers.IO) {
        val normalizedUsername = username.lowercase().trim()
        try {
            firestore.runTransaction { transaction ->
                val usernameRef = usernamesCollection.document(normalizedUsername)
                val userRef = usersCollection.document(userId)

                // 1. Check if the username is already taken
                val usernameDoc = transaction.get(usernameRef)
                if (usernameDoc.exists()) {
                    val existingUserId = usernameDoc.getString("userId")
                    if (existingUserId != userId) {
                        throw Exception("Username already taken")
                    }
                }

                // 2. Get current user to find old username for cleanup
                val userDoc = transaction.get(userRef)
                val oldUsername = userDoc.getString("displayName")?.lowercase()?.trim()

                // 3. Reserve the new username
                transaction.set(usernameRef, mapOf(
                    "userId" to userId,
                    "createdAt" to FieldValue.serverTimestamp()
                ))

                // 4. Update the user profile
                transaction.update(userRef, mapOf(
                    "displayName" to username,
                    "anonymousName" to username, // Sync for safety
                    "isAnonymous" to false, // They've chosen a name, though still "artifact" anonymous
                    "usernameUpdatedAt" to FieldValue.serverTimestamp()
                ))

                // 5. Clean up old username reservation
                if (oldUsername != null && oldUsername != normalizedUsername) {
                    transaction.delete(usernamesCollection.document(oldUsername))
                }
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
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
            android.util.Log.e("UserRepository", "Error checking username availability", e)
            // Default to false on error to be safe, or true if we want to allow retry on submit
            false
        }
    }

    /**
     * Checks if a user profile document exists in Firestore.
     * Used for first-time user detection and routing logic.
     */
    suspend fun isProfileCreated(userId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val doc = usersCollection.document(userId).get().await()
            doc.exists()
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Error checking profile existence", e)
            false
        }
    }

    suspend fun getOrCreateProfile(): User {
        // 1. Ensure Auth
        // If we have a user, try to reload to verify the session is still valid
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

        // 2. Atomic Check & Create via Transaction
        return firestore.runTransaction { transaction ->
            val snapshot = transaction.get(userRef)
            
            if (snapshot.exists()) {
                // Safe deserialization
                snapshot.toObject(User::class.java)?.copy(id = currentUser.uid)
                    ?: throw IllegalStateException("User document exists but is malformed.")
            } else {
                // Initialize new fully anonymous profile
                val anonymousName = NameGenerator.generate()
                val safePalette = listOf("#FADADD", "#E6E6FA", "#D1EAF0", "#E2F0D9", "#FFF4E0")
                
                val newProfile = User(
                    id = currentUser.uid,
                    anonymousName = anonymousName,
                    displayName = anonymousName, // Sync for legacy compatibility if needed
                    email = currentUser.email ?: "",
                    avatarColor = safePalette.random(),
                    isAnonymous = true,
                    emotionalProfile = "New Soul"
                )
                transaction.set(userRef, newProfile)
                newProfile
            }
        }.await()
    }

    /**
     * Streams the user profile in real-time from Firestore.
     */
    fun streamUserProfile(userId: String): Flow<User?> = callbackFlow {
        val docRef = usersCollection.document(userId)
        
        val registration = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                android.util.Log.e("UserRepository", "Error streaming user profile", error)
                trySend(null)
                return@addSnapshotListener
            }

            if (snapshot != null && (snapshot.exists())) {
                val user = snapshot.toObject(User::class.java)?.copy(id = userId)
                trySend(user)
            } else {
                trySend(null)
            }
        }

        awaitClose { registration.remove() }
    }

    /**
     * Establishes a follow relationship between two users atomically.
     */
    suspend fun followUser(currentUserId: String, targetUserId: String): Result<Unit> {
        if (currentUserId == targetUserId) return Result.failure(Exception("Cannot follow yourself"))

        return try {
            firestore.runTransaction { transaction ->
                val currentUserRef = usersCollection.document(currentUserId)
                val targetUserRef = usersCollection.document(targetUserId)
                
                val followingRef = currentUserRef.collection("following").document(targetUserId)
                val followersRef = targetUserRef.collection("followers").document(currentUserId)

                val followingDoc = transaction.get(followingRef)
                if (followingDoc.exists()) return@runTransaction // Already following

                // 1. Create relationship markers
                val timestamp = FieldValue.serverTimestamp()
                transaction.set(followingRef, mapOf("createdAt" to timestamp))
                transaction.set(followersRef, mapOf("createdAt" to timestamp))

                // 2. Increment counters
                transaction.update(currentUserRef, "followingCount", FieldValue.increment(1))
                transaction.update(targetUserRef, "followersCount", FieldValue.increment(1))
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Removes a follow relationship between two users atomically.
     */
    suspend fun unfollowUser(currentUserId: String, targetUserId: String): Result<Unit> {
        return try {
            firestore.runTransaction { transaction ->
                val currentUserRef = usersCollection.document(currentUserId)
                val targetUserRef = usersCollection.document(targetUserId)
                
                val followingRef = currentUserRef.collection("following").document(targetUserId)
                val followersRef = targetUserRef.collection("followers").document(currentUserId)

                val followingDoc = transaction.get(followingRef)
                if (!followingDoc.exists()) return@runTransaction // Not following

                // 1. Remove relationship markers
                transaction.delete(followingRef)
                transaction.delete(followersRef)

                // 2. Decrement counters (safely)
                val currentUserDoc = transaction.get(currentUserRef)
                val targetUserDoc = transaction.get(targetUserRef)

                val currentFollowingCount = currentUserDoc.getLong("followingCount") ?: 0L
                val targetFollowersCount = targetUserDoc.getLong("followersCount") ?: 0L

                transaction.update(currentUserRef, "followingCount", (currentFollowingCount - 1).coerceAtLeast(0))
                transaction.update(targetUserRef, "followersCount", (targetFollowersCount - 1).coerceAtLeast(0))
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Checks if the current user is following the target user.
     */
    suspend fun isFollowing(currentUserId: String, targetUserId: String): Boolean {
        return try {
            val doc = usersCollection.document(currentUserId)
                .collection("following").document(targetUserId)
                .get().await()
            doc.exists()
        } catch (_: Exception) {
            false
        }
    }
}
