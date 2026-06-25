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
import com.saurabh.artifact.domain.auth.SessionConstants
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.ReactionRepository
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
    private val reactionRepository: ReactionRepository,
    private val artifactRepository: ArtifactRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext Result.failure()
        val pending = pendingInteractionDao.getAllPending()

        if (pending.isEmpty()) return@withContext Result.success()

        val workerId = id.toString()
        var hasPermanentFailure = false
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
                val errorInteraction = processingInteraction.copy(lastError = error.message)
                
                ArtifactLogger.logInteraction(errorInteraction, "FAILURE", mapOf("error" to error.message))
                
                if (ArtifactRepository.isTransientError(error)) {
                    hasTransientFailure = true
                    // Update retry count in DB for the next run
                    pendingInteractionDao.insert(errorInteraction)
                    break
                } else {
                    // Permanent error (e.g. 404, 403), remove from queue to avoid blocking
                    pendingInteractionDao.delete(interaction)
                    hasPermanentFailure = true
                }
            }
        }

        when {
            hasTransientFailure -> Result.retry()
            hasPermanentFailure -> Result.success() // Partially succeeded, permanent failures discarded
            else -> Result.success()
        }
    }

    private suspend fun processInteraction(interaction: PendingInteractionEntity, userId: String): KResult<Unit> {
        return try {
            when (interaction.interactionType) {
                InteractionType.REACTION -> {
                    if (interaction.action == InteractionAction.ADD) {
                        val type = interaction.metadata?.let { ReactionType.fromId(it) } ?: ReactionType.I_HEAR_YOU
                        reactionRepository.reactToArtifact(interaction.artifactId, userId, type)
                    } else {
                        reactionRepository.removeReaction(interaction.artifactId, userId)
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
                else -> throw Exception("Unknown interaction type: ${interaction.interactionType}")
            }
            KResult.success(Unit)
        } catch (e: Exception) {
            KResult.failure(e)
        }
    }
    
    companion object {
        const val TAG = "InteractionSyncWorker"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<InteractionSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .addTag(SessionConstants.TAG_USER_SESSION_WORK)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                TAG,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
