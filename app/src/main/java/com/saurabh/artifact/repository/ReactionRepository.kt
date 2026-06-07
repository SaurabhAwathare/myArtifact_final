package com.saurabh.artifact.repository

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReactionRepository @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val firestore: FirebaseFirestore,
    private val notificationRepository: NotificationRepository,
    private val pendingInteractionDao: com.saurabh.artifact.data.local.PendingInteractionDao
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
                    message = "RESONANCE|${type.id}", // UI layer will map type
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
     * Removes an emotional reaction from an artifact.
     * Uses a transaction to ensure count consistency across private/global collections.
     */
    suspend fun removeReaction(
        artifactId: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val pulseRef = firestore.collection("users").document(userId)
                .collection("private").document("interactions")
                .collection("reactions").document(artifactId)
            
            val reactionId = "${artifactId}_${userId}"
            val globalRef = firestore.collection("artifact_reactions").document(reactionId)
            
            firestore.runTransaction { transaction ->
                transaction.delete(pulseRef)
                transaction.delete(globalRef)
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ReactionRepository", "Failed to remove reaction", e)
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
     * OFFLINE-FIRST: Writes to local pending queue and triggers sync worker.
     */
    suspend fun toggleReaction(artifactId: String, userId: String, type: ReactionType): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val pulseRef = firestore.collection("users").document(userId)
                .collection("private").document("interactions")
                .collection("reactions").document(artifactId)
            
            val existingPulseDoc = pulseRef.get().await()
            val willAdd = !existingPulseDoc.exists()

            // 1. Record pending interaction for sync
            val pending = com.saurabh.artifact.data.local.PendingInteractionEntity(
                artifactId = artifactId,
                interactionType = com.saurabh.artifact.data.local.InteractionType.REACTION,
                action = if (willAdd) com.saurabh.artifact.data.local.InteractionAction.ADD else com.saurabh.artifact.data.local.InteractionAction.REMOVE,
                metadata = type.id
            )
            
            // Clean up any existing pending reactions for this artifact to avoid redundant toggles
            pendingInteractionDao.deleteByType(artifactId, com.saurabh.artifact.data.local.InteractionType.REACTION)
            pendingInteractionDao.insert(pending)

            // 2. Trigger Sync Worker
            com.saurabh.artifact.worker.InteractionSyncWorker.enqueue(context)

            // 3. Optimistic success
            Result.success(Unit)
        } catch (e: Exception) {
            // Fallback for offline: if network fails, we still record the pending intent
            if (ArtifactRepository.isTransientError(e)) {
                val pending = com.saurabh.artifact.data.local.PendingInteractionEntity(
                    artifactId = artifactId,
                    interactionType = com.saurabh.artifact.data.local.InteractionType.REACTION,
                    action = com.saurabh.artifact.data.local.InteractionAction.ADD, // Assumption: most toggles are additions when offline
                    metadata = type.id
                )
                pendingInteractionDao.insert(pending)
                com.saurabh.artifact.worker.InteractionSyncWorker.enqueue(context)
                Result.success(Unit)
            } else {
                Log.e("ReactionRepository", "Failed to toggle reaction", e)
                Result.failure(e)
            }
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
