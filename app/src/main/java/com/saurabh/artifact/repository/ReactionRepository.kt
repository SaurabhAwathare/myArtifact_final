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
    private val firestore: FirebaseFirestore,
    private val notificationRepository: NotificationRepository
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

            // 1. References for Second-Order Privacy (Pulse & Echo)
            // Pulse: Private state at /users/{uid}/private/interactions/reactions/{artifactId}
            val pulseRef = firestore.collection("users").document(userId)
                .collection("private").document("interactions")
                .collection("reactions").document(artifactId)
            
            val artifactRef = firestore.collection("artifacts").document(artifactId)
            val artifactDoc = artifactRef.get().await()
            val ownerId = artifactDoc.getString("userId")

            firestore.runTransaction { transaction ->
                // A. Save the individual reaction in Private Pulse (OWNER ONLY)
                transaction.set(pulseRef, mapOf(
                    "id" to reactionId,
                    "artifactId" to artifactId,
                    "userId" to userId,
                    "type" to type.id,
                    "isPrivatePulse" to true,
                    "createdAt" to FieldValue.serverTimestamp()
                ))

                // B. Maintain 'artifact_reactions' (Consolidated name) for global visibility
                val globalRef = firestore.collection("artifact_reactions").document(reactionId)
                transaction.set(globalRef, mapOf(
                    "artifactId" to artifactId,
                    "userId" to userId,
                    "artifactOwnerId" to ownerId,
                    "type" to type.id,
                    "createdAt" to FieldValue.serverTimestamp()
                ))
            }.await()

            // Notify owner if it's not their own artifact
            if (ownerId != null && ownerId != userId) {
                notificationRepository.createNotification(
                    userId = ownerId,
                    message = notificationRepository.getAtmosphericMessage(type),
                    artifactId = artifactId,
                    type = NotificationType.RESONANCE
                )
            }

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
     * Returns a flow of all individual reactions for an artifact.
     * DEPRECATED: For Second-Order Privacy, public listing of reactions is removed.
     * This now only returns the current user's reaction if it exists.
     */
    fun getArtifactReactions(artifactId: String, userId: String): Flow<List<ArtifactReaction>> = callbackFlow {
        val pulseRef = firestore.collection("users").document(userId)
            .collection("private").document("interactions")
            .collection("reactions").document(artifactId)
        
        val subscription = pulseRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                trySend(emptyList())
                return@addSnapshotListener
            }
            val reactions = if (snapshot != null && snapshot.exists()) {
                listOf(snapshot.toObject(ArtifactReaction::class.java)!!.copy(id = snapshot.id))
            } else {
                emptyList()
            }
            trySend(reactions)
        }
        awaitClose { subscription.remove() }
    }

    /**
     * Toggles a reaction for a user on an artifact.
     */
    suspend fun toggleReaction(artifactId: String, userId: String, type: ReactionType): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val pulseRef = firestore.collection("users").document(userId)
                .collection("private").document("interactions")
                .collection("reactions").document(artifactId)
            
            val existingPulseDoc = pulseRef.get().await()

            if (existingPulseDoc.exists()) {
                val reactionId = "${artifactId}_${userId}"
                val globalRef = firestore.collection("artifact_reactions").document(reactionId)
                
                firestore.runTransaction { transaction ->
                    // 1. Delete reaction from both places
                    transaction.delete(pulseRef)
                    transaction.delete(globalRef)
                }.await()
            } else {
                // Add reaction
                reactToArtifact(artifactId, userId, type)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ReactionRepository", "Failed to toggle reaction", e)
            Result.failure(e)
        }
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
