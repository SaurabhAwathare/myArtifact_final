package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlin.Result as KResult
import com.google.firebase.auth.FirebaseAuth
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.model.SyncState
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.EngagementRepository
import com.saurabh.artifact.repository.FirestoreEngagementRepository
import com.saurabh.artifact.repository.ReactionRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.diagnostics.logInteraction
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@HiltWorker
class InteractionSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val pendingInteractionDao: PendingInteractionDao,
    private val deadLetterInteractionDao: DeadLetterInteractionDao,
    private val reactionRepository: ReactionRepository,
    private val artifactRepository: ArtifactRepository,
    private val engagementRepository: EngagementRepository,
    private val firestoreEngagementRepository: FirestoreEngagementRepository,
    private val userRepository: UserRepository,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext Result.failure()
        diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "SYNC_STARTED", mapOf("worker" to "InteractionSyncWorker"))
        
        var isRetryRequired = false

        // 1. Sync Engagement Evidence (Phase 1)
        if (!syncEngagement(currentUserId)) {
            isRetryRequired = true
        }

        // 2. Collapse duplicate/redundant interaction events before processing
        collapseEvents(currentUserId)

        val pending = pendingInteractionDao.getPendingForUser(currentUserId)
        if (pending.isEmpty() && !isRetryRequired) return@withContext Result.success()

        val workerId = id.toString()
        var hasInteractionTransientFailure = false

        for (interaction in pending) {
            val processingInteraction = interaction.copy(
                workerId = workerId,
                retryCount = interaction.retryCount + 1
            )
            
            diagnosticLogger.logInteraction(processingInteraction, "PROCESSING")

            val result = processInteraction(processingInteraction, currentUserId)
            if (result.isSuccess) {
                diagnosticLogger.logInteraction(processingInteraction, "SUCCESS")
                pendingInteractionDao.delete(interaction)
            } else {
                val error = result.exceptionOrNull() ?: Exception("Unknown error")
                val isTransient = ArtifactRepository.isTransientError(error)
                
                val errorInteraction = processingInteraction.copy(
                    lastError = error.message,
                    retryCount = processingInteraction.retryCount
                )
                
                if (isTransient) {
                    if (errorInteraction.retryCount >= MAX_RETRIES) {
                        diagnosticLogger.logInteraction(errorInteraction, "RETRY_LIMIT_EXCEEDED", mapOf("error" to error.message, "exception" to error.javaClass.simpleName))
                        moveToDeadLetterQueue(errorInteraction, "RETRY_LIMIT_EXCEEDED", error.message)
                        pendingInteractionDao.delete(interaction)
                    } else {
                        diagnosticLogger.logInteraction(errorInteraction, "TRANSIENT_FAILURE", mapOf("error" to error.message, "exception" to error.javaClass.simpleName))
                        // Update retry count and error in DB for the next run
                        pendingInteractionDao.insert(errorInteraction)
                        hasInteractionTransientFailure = true
                        
                        // CRITICAL: Break on transient failure to preserve sequential ordering.
                        break
                    }
                } else {
                    // Permanent error (e.g. 404, 403)
                    diagnosticLogger.logInteraction(errorInteraction, "PERMANENT_FAILURE", mapOf("error" to error.message, "exception" to error.javaClass.simpleName))
                    moveToDeadLetterQueue(errorInteraction, "PERMANENT", error.message)
                    pendingInteractionDao.delete(interaction)
                }
            }
        }

        when {
            isRetryRequired || hasInteractionTransientFailure -> {
                diagnosticLogger.warn(DiagnosticCategory.WORKMANAGER, "SYNC_RETRY", mapOf("worker" to "InteractionSyncWorker"))
                Result.retry()
            }
            else -> {
                diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "SYNC_COMPLETED", mapOf("worker" to "InteractionSyncWorker"))
                Result.success() // Succeed even with terminal/DLQ failures to drain the queue
            }
        }
    }

    /**
     * Sweeps the engagement table for unsynced records and uploads them to Firestore.
     * Returns true if all processing completed (even if with terminal failures),
     * or false if a retry is required due to transient network issues.
     */
    private suspend fun syncEngagement(userId: String): Boolean {
        val pending = engagementRepository.getEngagementsRequiringSync()
        
        diagnosticLogger.info(
            DiagnosticCategory.SYNC,
            "INVESTIGATION_LOG",
            mapOf(
                "Stage" to "WorkerLoad",
                "PendingSyncCount" to pending.size,
                "ArtifactIds" to pending.map { it.artifactId }
            )
        )

        if (pending.isEmpty()) return true

        var hasTransientFailure = false

        for (evidence in pending) {
            val traceId = evidence.artifactId
            diagnosticLogger.info(
                DiagnosticCategory.SYNC,
                "INVESTIGATION_LOG",
                mapOf(
                    "TRACE_ID" to traceId,
                    "Stage" to "WorkerStart",
                    "AuthUID" to (FirebaseAuth.getInstance().currentUser?.uid ?: "null"),
                    "ArtifactId" to evidence.artifactId,
                    "Thread" to Thread.currentThread().name,
                    "Timestamp" to System.currentTimeMillis()
                )
            )

            diagnosticLogger.info(DiagnosticCategory.SYNC, "ENGAGEMENT_SYNC_START", mapOf("artifactId" to evidence.artifactId))
            
            engagementRepository.updateSyncStatus(evidence.artifactId, SyncState.SYNCING)
            
            val result = firestoreEngagementRepository.uploadEngagement(userId, evidence)
            
            if (result.isSuccess) {
                diagnosticLogger.info(DiagnosticCategory.SYNC, "ENGAGEMENT_SYNC_SUCCESS", mapOf<String, Any>("artifactId" to evidence.artifactId))
                engagementRepository.markEngagementSynced(evidence.artifactId)
            } else {
                val error = result.exceptionOrNull() ?: Exception("Unknown sync error")
                val isTransient = ArtifactRepository.isTransientError(error)
                
                if (isTransient) {
                    diagnosticLogger.warn(DiagnosticCategory.SYNC, "ENGAGEMENT_SYNC_TRANSIENT", mapOf<String, Any>("artifactId" to evidence.artifactId, "error" to (error.message ?: "unknown")))
                    engagementRepository.updateSyncStatus(evidence.artifactId, SyncState.PENDING, error.message)
                    hasTransientFailure = true
                } else {
                    diagnosticLogger.error(DiagnosticCategory.SYNC, "ENGAGEMENT_SYNC_PERMANENT", mapOf<String, Any>("artifactId" to evidence.artifactId, "error" to (error.message ?: "unknown")))
                    engagementRepository.updateSyncStatus(evidence.artifactId, SyncState.FAILED, error.message)
                }
            }
        }
        
        return !hasTransientFailure
    }

    private suspend fun moveToDeadLetterQueue(
        interaction: PendingInteractionEntity,
        failureType: String,
        reason: String?
    ) {
        val dlqEntry = DeadLetterInteractionEntity(
            userId = interaction.userId,
            originalId = interaction.id,
            artifactId = interaction.artifactId,
            interactionType = interaction.interactionType,
            action = interaction.action,
            metadata = interaction.metadata,
            createdAt = interaction.createdAt,
            correlationId = interaction.correlationId,
            failureReason = reason,
            failureType = failureType,
            retryCount = interaction.retryCount
        )
        deadLetterInteractionDao.insert(dlqEntry)
        diagnosticLogger.warn(DiagnosticCategory.SYNC, "DLQ_MOVE", mapOf("interactionId" to interaction.id, "failureType" to failureType, "reason" to (reason ?: "unknown")))
    }

    /**
     * Collapses redundant toggle events in the local queue to reduce write amplification.
     */
    internal suspend fun collapseEvents(userId: String) {
        val allPending = pendingInteractionDao.getPendingForUser(userId)
        if (allPending.isEmpty()) return

        // Group by target (artifactId) and interaction type
        val groups = allPending.groupBy { "${it.artifactId}_${it.interactionType}" }

        groups.forEach { (key, events) ->
            if (events.size <= 1) return@forEach

            // Check if this type is collapsible
            val type = events.first().interactionType
            if (!isCollapsible(type)) {
                diagnosticLogger.debug(DiagnosticCategory.SYNC, "COLLAPSE_SKIPPED_NOT_COLLAPSIBLE", mapOf("type" to type, "key" to key))
                return@forEach
            }

            val first = events.first()
            val latest = events.last()
            val toDelete = events.dropLast(1)
            
            val isRedundantCycle = (events.size == 2) && 
                (first.action == InteractionAction.ADD) && 
                (latest.action == InteractionAction.REMOVE)

            if (isRedundantCycle) {
                // Perfect net-zero cycle [ADD, REMOVE]
                events.forEach { pendingInteractionDao.delete(it) }
                diagnosticLogger.info(DiagnosticCategory.SYNC, "COLLAPSE_CYCLE_CANCELLED", mapOf("key" to key))
            } else {
                // Collapse to the latest intent
                toDelete.forEach { pendingInteractionDao.delete(it) }
                diagnosticLogger.info(DiagnosticCategory.SYNC, "COLLAPSE_COMPLETED", mapOf("key" to key, "count" to events.size, "latestAction" to latest.action))
            }
        }
    }

    private fun isCollapsible(type: String): Boolean {
        return type == InteractionType.SAVE || 
               type == InteractionType.REACTION || 
               type == InteractionType.FOLLOW
    }

    /**
     * Processes a single interaction by calling INTERNAL SYNC APIs.
     * ARCHITECTURAL INVARIANT: Must NEVER call a repository method that re-enqueues.
     */
    private suspend fun processInteraction(interaction: PendingInteractionEntity, userId: String): KResult<Unit> {
        return try {
            val result = when (interaction.interactionType) {
                InteractionType.REACTION -> {
                    if (interaction.action == InteractionAction.ADD) {
                        val type = interaction.metadata?.let { ReactionType.fromId(it) } ?: ReactionType.I_HEAR_YOU
                        reactionRepository.syncReactionToFirestore(interaction.artifactId, userId, type)
                    } else {
                        reactionRepository.syncReactionRemovalFromFirestore(interaction.artifactId, userId)
                    }
                }
                InteractionType.SAVE -> {
                    if (interaction.action == InteractionAction.ADD) {
                        artifactRepository.saveArtifactToFirestore(
                            userId = userId,
                            artifactId = interaction.artifactId,
                            shelf = interaction.metadata ?: "Stayed With Me",
                        )
                    } else {
                        artifactRepository.unsaveArtifactFromFirestore(userId, interaction.artifactId)
                    }
                }
                InteractionType.FOLLOW -> {
                    val targetUserId = interaction.artifactId
                    if (interaction.action == InteractionAction.ADD) {
                        userRepository.syncFollowToFirestore(userId, targetUserId)
                    } else {
                        userRepository.syncUnfollowFromFirestore(userId, targetUserId)
                    }
                }
                else -> throw Exception("Unknown interaction type: ${interaction.interactionType}")
            }
            
            if (result.isSuccess) KResult.success(Unit) else KResult.failure(result.exceptionOrNull() ?: Exception("Sync failed"))
        } catch (e: Exception) {
            KResult.failure(e)
        }
    }
    
    companion object {
        const val TAG = "InteractionSyncWorker"
        const val MAX_RETRIES = 5

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<InteractionSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                TAG,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
