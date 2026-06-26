package com.saurabh.artifact.repository

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.model.*
import com.saurabh.artifact.util.ArtifactLogger
import com.saurabh.artifact.util.RefactorFeatureFlags
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
    private val pendingInteractionDao: com.saurabh.artifact.data.local.PendingInteractionDao
) {

    /**
     * Submits an emotional reaction to an artifact.
     * PUBLIC API: Used by ViewModels. Enqueues interaction if unified queue is enabled.
     */
    suspend fun reactToArtifact(
        artifactId: String,
        userId: String,
        type: ReactionType
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (RefactorFeatureFlags.USE_UNIFIED_INTERACTION_QUEUE) {
                val pending = com.saurabh.artifact.data.local.PendingInteractionEntity(
                    userId = userId,
                    artifactId = artifactId,
                    interactionType = com.saurabh.artifact.data.local.InteractionType.REACTION,
                    action = com.saurabh.artifact.data.local.InteractionAction.ADD,
                    metadata = type.id
                )
                pendingInteractionDao.deleteByType(artifactId, userId, com.saurabh.artifact.data.local.InteractionType.REACTION)
                pendingInteractionDao.insert(pending)
                com.saurabh.artifact.worker.InteractionSyncWorker.enqueue(context)
                
                ArtifactLogger.i("ReactionRepository", "Reaction interaction queued locally for $artifactId")
                return@withContext Result.success(Unit)
            }

            syncReactionToFirestore(artifactId, userId, type)
        } catch (e: Exception) {
            Log.e("ReactionRepository", "Failed to react to artifact", e)
            Result.failure(e)
        }
    }

    /**
     * Removes an emotional reaction from an artifact.
     * PUBLIC API: Used by ViewModels. Enqueues interaction if unified queue is enabled.
     */
    suspend fun removeReaction(
        artifactId: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (RefactorFeatureFlags.USE_UNIFIED_INTERACTION_QUEUE) {
                val pending = com.saurabh.artifact.data.local.PendingInteractionEntity(
                    userId = userId,
                    artifactId = artifactId,
                    interactionType = com.saurabh.artifact.data.local.InteractionType.REACTION,
                    action = com.saurabh.artifact.data.local.InteractionAction.REMOVE
                )
                pendingInteractionDao.deleteByType(artifactId, userId, com.saurabh.artifact.data.local.InteractionType.REACTION)
                pendingInteractionDao.insert(pending)
                com.saurabh.artifact.worker.InteractionSyncWorker.enqueue(context)
                
                ArtifactLogger.i("ReactionRepository", "Reaction removal interaction queued locally for $artifactId")
                return@withContext Result.success(Unit)
            }

            syncReactionRemovalFromFirestore(artifactId, userId)
        } catch (e: Exception) {
            Log.e("ReactionRepository", "Failed to remove reaction", e)
            Result.failure(e)
        }
    }

    /**
     * Internal synchronization method for reactions.
     * INTERNAL SYNC API: Intended exclusively for InteractionSyncWorker.
     * Performs direct Firestore write without enqueuing.
     */
    internal suspend fun syncReactionToFirestore(
        artifactId: String,
        userId: String,
        type: ReactionType
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // PRE-QUEUE INTENT WRITE
            val intentRef = firestore.collection("users").document(userId)
                .collection("private").document("intents")
                .collection("reactions").document(artifactId)
            
            intentRef.set(mapOf(
                "artifactId" to artifactId,
                "type" to type.id,
                "action" to "ADD",
                "timestamp" to FieldValue.serverTimestamp()
            )).await()
            
            // Pulse: Private state (Still client-owned for optimistic UI/privacy)
            val pulseRef = firestore.collection("users").document(userId)
                .collection("private").document("interactions")
                .collection("reactions").document(artifactId)
            
            pulseRef.set(mapOf(
                "artifactId" to artifactId,
                "userId" to userId,
                "type" to type.id,
                "isPrivatePulse" to true,
                "createdAt" to FieldValue.serverTimestamp()
            )).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Internal synchronization method for reaction removal.
     * INTERNAL SYNC API: Intended exclusively for InteractionSyncWorker.
     * Performs direct Firestore write without enqueuing.
     */
    internal suspend fun syncReactionRemovalFromFirestore(
        artifactId: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // PRE-QUEUE INTENT WRITE: DELETE to trigger onReactionIntentDeleted
            val intentRef = firestore.collection("users").document(userId)
                .collection("private").document("intents")
                .collection("reactions").document(artifactId)
            
            intentRef.delete().await()
            
            val pulseRef = firestore.collection("users").document(userId)
                .collection("private").document("interactions")
                .collection("reactions").document(artifactId)
            
            pulseRef.delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
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
                userId = userId,
                artifactId = artifactId,
                interactionType = com.saurabh.artifact.data.local.InteractionType.REACTION,
                action = if (willAdd) com.saurabh.artifact.data.local.InteractionAction.ADD else com.saurabh.artifact.data.local.InteractionAction.REMOVE,
                metadata = type.id
            )
            
            // Clean up any existing pending reactions for this artifact to avoid redundant toggles
            pendingInteractionDao.deleteByType(artifactId, userId, com.saurabh.artifact.data.local.InteractionType.REACTION)
            pendingInteractionDao.insert(pending)

            // 2. Trigger Sync Worker
            com.saurabh.artifact.worker.InteractionSyncWorker.enqueue(context)

            // 3. Optimistic success
            Result.success(Unit)
        } catch (e: Exception) {
            // Fallback for offline: if network fails, we still record the pending intent
            if (ArtifactRepository.isTransientError(e)) {
                val pending = com.saurabh.artifact.data.local.PendingInteractionEntity(
                    userId = userId,
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
