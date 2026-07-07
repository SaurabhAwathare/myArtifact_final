package com.saurabh.artifact.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.saurabh.artifact.util.ArtifactLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
     * The listener lifecycle is driven by the authentication state to prevent PERMISSION_DENIED
     * errors during logout transitions.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val unlockedArtifactIds: Flow<Set<String>> = authRepository.currentUser
        .flatMapLatest { user ->
            val userId = user?.uid
            if (userId == null || userId.isEmpty()) {
                ArtifactLogger.d("CommentUnlockRepository", "No authenticated user, emitting empty unlocked set")
                flowOf(emptySet())
            } else {
                observeUnlockedArtifacts(userId)
            }
        }

    /**
     * Internal helper to observe the engagement sub-collection for a specific user.
     */
    private fun observeUnlockedArtifacts(userId: String): Flow<Set<String>> = callbackFlow {
        ArtifactLogger.d("CommentUnlockRepository", "Attaching listener for user: $userId")
        
        val listener = firestore.collection("users").document(userId)
            .collection("engagement")
            .whereEqualTo("isCommentUnlocked", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // During logout transitions, a transient PERMISSION_DENIED may still occur
                    // before the flow cancellation propagates. We log these as debug to reduce noise.
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        ArtifactLogger.d("CommentUnlockRepository", "Permission denied during observation (expected during auth transition)")
                    } else {
                        ArtifactLogger.e("CommentUnlockRepository", "Error observing unlocked artifacts", error)
                    }
                    return@addSnapshotListener
                }
                
                val ids = snapshot?.documents?.mapNotNull { it.getString("artifactId") }?.toSet() ?: emptySet()
                ArtifactLogger.d("CommentUnlockRepository", "Unlocked artifacts updated: ${ids.size} items")
                trySend(ids)
            }

        awaitClose {
            ArtifactLogger.d("CommentUnlockRepository", "Removing listener for user: $userId")
            listener.remove()
        }
    }

    /**
     * Checks if a specific artifact is unlocked.
     */
    fun isUnlocked(artifactId: String): Flow<Boolean> = unlockedArtifactIds.map { it.contains(artifactId) }
}
