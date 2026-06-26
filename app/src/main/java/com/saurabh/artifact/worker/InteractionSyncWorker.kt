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
import com.google.gson.Gson
import com.saurabh.artifact.domain.review.EngagementSyncPayload
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.ReactionRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.repository.EngagementRepository
import com.saurabh.artifact.util.ArtifactLogger
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
    private val userRepository: UserRepository,
    private val engagementRepository: EngagementRepository,
    private val gson: Gson
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext Result.failure()
        
        // 1. Collapse duplicate/redundant events before processing
        collapseEvents(userId)

        val pending = pendingInteractionDao.getPendingForUser(userId)
        if (pending.isEmpty()) return@withContext Result.success()

        val workerId = id.toString()
        var hasTransientFailure = false

        for (interaction in pending) {
            val processingInteraction = interaction.copy(
                workerId = workerId,
                retryCount = interaction.retryCount + 1
            )
            
            ArtifactLogger.logInteraction(processingInteraction, "PROCESSING")

            val result = processInteraction(processingInteraction, userId)
            if (result.isSuccess) {
                ArtifactLogger.logInteraction(processingInteraction, "SUCCESS")
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
                        ArtifactLogger.logInteraction(errorInteraction, "RETRY_LIMIT_EXCEEDED", mapOf("error" to error.message, "exception" to error.javaClass.simpleName))
                        moveToDeadLetterQueue(errorInteraction, "RETRY_LIMIT_EXCEEDED", error.message)
                        pendingInteractionDao.delete(interaction)
                    } else {
                        ArtifactLogger.logInteraction(errorInteraction, "TRANSIENT_FAILURE", mapOf("error" to error.message, "exception" to error.javaClass.simpleName))
                        // Update retry count and error in DB for the next run
                        pendingInteractionDao.insert(errorInteraction)
                        hasTransientFailure = true
                        
                        // CRITICAL: Break on transient failure to preserve sequential ordering.
                        break
                    }
                } else {
                    // Permanent error (e.g. 404, 403)
                    ArtifactLogger.logInteraction(errorInteraction, "PERMANENT_FAILURE", mapOf("error" to error.message, "exception" to error.javaClass.simpleName))
                    moveToDeadLetterQueue(errorInteraction, "PERMANENT", error.message)
                    pendingInteractionDao.delete(interaction)
                }
            }
        }

        when {
            hasTransientFailure -> Result.retry()
            else -> Result.success() // Succeed even with terminal/DLQ failures to drain the queue
        }
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
        ArtifactLogger.w(TAG, "Interaction ${interaction.id} moved to DLQ. Type: $failureType. Reason: $reason")
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
                ArtifactLogger.d(TAG, "Type $type is not collapsible. Preserving order for $key.")
                return@forEach
            }

            val latest = events.last()
            val toDelete = events.dropLast(1)
            
            val isRedundantCycle = events.size >= 2 && 
                events.first().action != latest.action && 
                events.all { it.interactionType == type }

            if (isRedundantCycle && events.size == 2) {
                // Perfect cycle [ADD, REMOVE] or [REMOVE, ADD]
                events.forEach { pendingInteractionDao.delete(it) }
                ArtifactLogger.i(TAG, "Cancelled out redundant toggle cycle for $key.")
            } else {
                // Collapse to the latest intent
                toDelete.forEach { pendingInteractionDao.delete(it) }
                ArtifactLogger.i(TAG, "Collapsed ${events.size} events for $key into 1 latest event (${latest.action}).")
            }
        }
    }

    private fun isCollapsible(type: String): Boolean {
        return type == InteractionType.SAVE || 
               type == InteractionType.REACTION || 
               type == InteractionType.FOLLOW ||
               type == InteractionType.ENGAGEMENT
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
                InteractionType.ENGAGEMENT -> {
                    val payloadJson = interaction.metadata ?: throw Exception("Engagement metadata missing")
                    val payload = gson.fromJson(payloadJson, EngagementSyncPayload::class.java)
                    engagementRepository.syncEngagementToFirestore(userId, payload)
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
