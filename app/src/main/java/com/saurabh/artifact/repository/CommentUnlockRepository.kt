package com.saurabh.artifact.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.util.ArtifactLogger
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
     * Observed from the user's private 'engagement' sub-collection in Firestore.
     */
    val unlockedArtifactIds: Flow<Set<String>> = callbackFlow {
        val userId = authRepository.currentUserId
        if (userId.isEmpty()) {
            trySend(emptySet())
            return@callbackFlow
        }

        // Migration: Now querying the nested engagement path
        val listener = firestore.collection("users").document(userId)
            .collection("engagement")
            .whereEqualTo("isCommentUnlocked", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    ArtifactLogger.e("CommentUnlockRepository", "Error observing unlocked artifacts", error)
                    return@addSnapshotListener
                }
                val ids = snapshot?.documents?.mapNotNull { it.getString("artifactId") }?.toSet() ?: emptySet()
                ArtifactLogger.d("CommentUnlockRepository", "Unlocked artifacts updated: ${ids.size} items")
                trySend(ids)
            }

        awaitClose { listener.remove() }
    }

    /**
     * Checks if a specific artifact is unlocked.
     */
    fun isUnlocked(artifactId: String): Flow<Boolean> = unlockedArtifactIds.map { it.contains(artifactId) }
}
