package com.saurabh.artifact.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.saurabh.artifact.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReactionRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    /**
     * Submits an emotional reaction to an artifact.
     * Uses a transaction to ensure count consistency if possible, 
     * but relies on Cloud Functions for global aggregation scaling.
     */
    suspend fun reactToArtifact(
        artifactId: String,
        userId: String,
        type: ReactionType
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val reactionId = "${artifactId}_${userId}"
            val reaction = ArtifactReaction(
                id = reactionId,
                artifactId = artifactId,
                userId = userId,
                type = type,
                createdAt = Timestamp.now()
            )

            // 1. Save the individual reaction
            firestore.collection("artifact_reactions").document(reactionId)
                .set(reaction)
                .await()

            // 2. Optimistically update the aggregate (Local cache benefit)
            // Note: In production, Cloud Functions would do the heavy lifting for global aggregation.
            val aggregateRef = firestore.collection("artifact_reaction_counts").document(artifactId)
            
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(aggregateRef)
                val currentTotal = snapshot.getLong("totalCount") ?: 0L
                val currentBreakdown = (snapshot.get("breakdown") as? Map<String, Long>) ?: emptyMap()
                
                val newBreakdown = currentBreakdown.toMutableMap()
                newBreakdown[type.name] = (newBreakdown[type.name] ?: 0L) + 1
                
                transaction.set(
                    aggregateRef,
                    mapOf(
                        "totalCount" to currentTotal + 1,
                        "breakdown" to newBreakdown,
                        "lastUpdated" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ReactionRepository", "Failed to react to artifact", e)
            Result.failure(e)
        }
    }

    /**
     * Listens to aggregated reaction counts for an artifact.
     */
    fun getReactionCounts(artifactId: String): Flow<ArtifactReactionCounts?> = callbackFlow {
        val docRef = firestore.collection("artifact_reaction_counts").document(artifactId)
        
        val subscription = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                trySend(null)
                return@addSnapshotListener
            }
            
            val counts = snapshot?.toObject(ArtifactReactionCounts::class.java)?.copy(artifactId = artifactId)
            trySend(counts)
        }
        awaitClose { subscription.remove() }
    }

    /**
     * Updates the visibility mode for an artifact's reactions.
     * Only callable by the creator (enforced by Firestore rules).
     */
    suspend fun setVisibilityMode(artifactId: String, mode: ReactionVisibilityMode): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            firestore.runBatch { batch ->
                // 1. Update reaction counts aggregate
                val countRef = firestore.collection("artifact_reaction_counts").document(artifactId)
                batch.update(countRef, "visibility", mode.name)
                
                // 2. Sync to main artifact document for efficient feed loading
                val artifactRef = firestore.collection("artifacts").document(artifactId)
                batch.update(artifactRef, "reactionVisibility", mode.name)
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ReactionRepository", "Failed to update visibility mode", e)
            Result.failure(e)
        }
    }
}
