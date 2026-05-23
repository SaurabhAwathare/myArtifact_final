package com.saurabh.artifact.repository

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentUnlockRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val authRepository: AuthRepository
) {
    /**
     * Returns a flow of the set of artifact IDs that have been unlocked for commenting.
     * Observed from the 'listening_sessions' collection in Firestore.
     */
    val unlockedArtifactIds: Flow<Set<String>> = callbackFlow {
        val userId = authRepository.currentUserId
        if (userId.isEmpty()) {
            trySend(emptySet())
            return@callbackFlow
        }

        val listener = firestore.collection("listening_sessions")
            .whereEqualTo("userId", userId)
            .whereEqualTo("isCompleted", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    return@addSnapshotListener
                }
                val ids = snapshot?.documents?.mapNotNull { it.getString("artifactId") }?.toSet() ?: emptySet()
                trySend(ids)
            }

        awaitClose { listener.remove() }
    }

    /**
     * Checks if a specific artifact is unlocked.
     */
    fun isUnlocked(artifactId: String): Flow<Boolean> = unlockedArtifactIds.map { it.contains(artifactId) }

    /**
     * Marks an artifact as unlocked by updating the listening session in Firestore.
     */
    fun unlockArtifact(artifactId: String) {
        val userId = authRepository.currentUserId
        if (userId.isEmpty()) return

        val sessionId = "${userId}_$artifactId"
        firestore.collection("listening_sessions")
            .document(sessionId)
            .update("isCompleted", true)
    }
}
