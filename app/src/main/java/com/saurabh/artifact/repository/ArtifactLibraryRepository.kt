package com.saurabh.artifact.repository

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.saurabh.artifact.data.paging.SavedArtifactPagingSource
import com.saurabh.artifact.data.local.InteractionAction
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.local.PendingInteractionEntity
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.util.CoroutineExceptionHandlerUtils
import com.saurabh.artifact.worker.InteractionSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtifactLibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestore: FirebaseFirestore,
    private val pendingInteractionDao: dagger.Lazy<PendingInteractionDao>,
    private val visibilityFilter: dagger.Lazy<com.saurabh.artifact.domain.ArtifactVisibilityFilter>,
    private val safetyPolicy: com.saurabh.artifact.domain.SafetyPolicy,
    private val diagnosticLogger: DiagnosticLogger
) {

    /**
     * Persists a private emotional bookmark for an artifact.
     * PUBLIC API: Used by ViewModels. Enqueues interaction if unified queue is enabled.
     */
    suspend fun saveArtifact(
        userId: String,
        artifact: Artifact,
        shelf: String = "Stayed With Me"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            diagnosticLogger.debug(DiagnosticCategory.RESONANCE, "ARTIFACT_SAVE_QUEUED", mapOf(LogKeys.ARTIFACT_ID to artifact.id))
            
            // 1. Record pending interaction
            val pending = PendingInteractionEntity(
                userId = userId,
                artifactId = artifact.id,
                interactionType = InteractionType.SAVE,
                action = InteractionAction.ADD,
                metadata = shelf
            )
            pendingInteractionDao.get().deleteByType(artifact.id, userId, InteractionType.SAVE)
            pendingInteractionDao.get().insert(pending)

            // 2. Trigger Sync Worker
            InteractionSyncWorker.enqueue(context)

            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RESONANCE, "ARTIFACT_SAVE_QUEUE_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifact.id), e)
            Result.failure(e)
        }
    }

    /**
     * Removes a private emotional bookmark.
     * PUBLIC API: Used by ViewModels. Enqueues interaction if unified queue is enabled.
     */
    suspend fun unsaveArtifact(userId: String, artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            diagnosticLogger.debug(DiagnosticCategory.RESONANCE, "ARTIFACT_UNSAVE_QUEUED", mapOf(LogKeys.ARTIFACT_ID to artifactId))
            
            // 1. Record pending interaction
            val pending = PendingInteractionEntity(
                userId = userId,
                artifactId = artifactId,
                interactionType = InteractionType.SAVE,
                action = InteractionAction.REMOVE
            )
            pendingInteractionDao.get().deleteByType(artifactId, userId, InteractionType.SAVE)
            pendingInteractionDao.get().insert(pending)

            // 2. Trigger Sync Worker
            InteractionSyncWorker.enqueue(context)

            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RESONANCE, "ARTIFACT_UNSAVE_QUEUE_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), e)
            Result.failure(e)
        }
    }

    /**
     * Internal synchronization method for artifact saving.
     * INTERNAL SYNC API: Intended exclusively for InteractionSyncWorker.
     * Performs direct Firestore write without enqueuing.
     */
    internal suspend fun syncSave(userId: String, artifactId: String, shelf: String = "Stayed With Me"): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val docRef = firestore.collection("users").document(userId)
                .collection("savedArtifacts").document(artifactId)

            docRef.set(mapOf(
                "savedAt" to Timestamp.now(),
                "shelf" to shelf
            )).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Internal synchronization method for artifact unsaving.
     * INTERNAL SYNC API: Intended exclusively for InteractionSyncWorker.
     * Performs direct Firestore write without enqueuing.
     */
    internal suspend fun syncUnsave(userId: String, artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            firestore.collection("users").document(userId)
                .collection("savedArtifacts").document(artifactId)
                .delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Streams the current user's saved artifact IDs for global UI synchronization.
     */
    fun getSavedArtifactIds(userId: String): Flow<Set<String>> = callbackFlow {
        val subscription = firestore.collection("users").document(userId)
            .collection("savedArtifacts")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptySet())
                    return@addSnapshotListener
                }
                val ids = snapshot?.documents?.asSequence()?.map { it.id }?.toSet() ?: emptySet()
                trySend(ids)
            }
        awaitClose { subscription.remove() }
    }

    /**
     * Provides a paginated stream of artifacts saved by the user.
     * Ensures O(1) query growth for the "Stayed With Me" library.
     */
    fun getSavedArtifactsPager(userId: String): Flow<PagingData<Artifact>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                prefetchDistance = 5,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                SavedArtifactPagingSource(
                    firestore = firestore,
                    userId = userId,
                    safetyPolicy = safetyPolicy,
                    visibilityFilter = visibilityFilter.get()
                )
            }
        ).flow
    }
}
