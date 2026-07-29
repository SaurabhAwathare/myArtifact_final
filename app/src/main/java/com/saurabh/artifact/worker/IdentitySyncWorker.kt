package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.repository.UserRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@HiltWorker
class IdentitySyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val firestore: FirebaseFirestore,
    private val userRepository: UserRepository,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val userId = inputData.getString(KEY_USER_ID) ?: return@withContext Result.failure()
        diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "IDENTITY_SYNC_STARTED", mapOf(LogKeys.USER_ID to userId))
        
        try {
            // 1. Fetch the latest profile from Firestore (Source of Truth)
            val userProfileResult = userRepository.getOrCreateProfile()
            val user = userProfileResult.getOrNull()?.user ?: return@withContext Result.retry()

            val workerVersion = inputData.getLong(KEY_VERSION, 0L)

            val name = user.anonymousName
            val anonymousId = user.anonymousId
            val sigil = user.anonymousSigil
            val sigilSeed = user.sigilSeed
            val sigilColor = user.sigilColor
            val sigilConfig = user.sigilConfig

            val authorUpdate = mapOf(
                "author.name" to name,
                "author.anonymousId" to anonymousId,
                "author.sigil" to sigil,
                "author.sigilSeed" to sigilSeed,
                "author.sigilColor" to sigilColor,
                "author.sigilConfig" to sigilConfig,
                // Lazy Cleanup: Remove legacy avatar fields during sync
                "author.avatarSeed" to FieldValue.delete(),
                "author.avatarColor" to FieldValue.delete(),
                "author.avatarConfig" to FieldValue.delete()
            )

            // 2. Sync Artifacts
            val artifactsQuery = firestore.collection("artifacts")
                .whereEqualTo("userId", userId)
                .get()
                .await()

            if (!artifactsQuery.isEmpty) {
                val batches = artifactsQuery.documents.chunked(BATCH_LIMIT)
                for (chunk in batches) {
                    val batch = firestore.batch()
                    for (doc in chunk) {
                        batch.update(doc.reference, authorUpdate)
                    }
                    batch.commit().await()
                }
            }

            // 3. Monotonic Update of lastCompletedIdentityVersion
            // Use fallback to identityResetVersion for recovery scenarios where version might not be in inputData
            val targetVersion = if (workerVersion > 0) workerVersion else user.identityMetadata.identityResetVersion
            
            if (targetVersion > 0) {
                val userRef = firestore.collection("users").document(userId)
                firestore.runTransaction { transaction ->
                    val snapshot = transaction[userRef]
                    val currentCompleted = snapshot.getLong("identityMetadata.lastCompletedIdentityVersion") ?: 0L
                    if (targetVersion > currentCompleted) {
                        transaction.update(userRef, mapOf(
                            "identityMetadata.lastCompletedIdentityVersion" to targetVersion,
                            "identityMetadata.resetCompletedAt" to FieldValue.serverTimestamp()
                        ))
                    }
                }.await()
            }

            Result.success()
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.WORKMANAGER, "IDENTITY_SYNC_FAILED", mapOf(LogKeys.USER_ID to userId), e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_USER_ID = "userId"
        const val KEY_VERSION = "version"
        private const val BATCH_LIMIT = 500

        fun enqueue(
            context: Context, 
            userId: String, 
            version: Long = 0,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<IdentitySyncWorker>()
                .setInputData(workDataOf(
                    KEY_USER_ID to userId,
                    KEY_VERSION to version
                ))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .addTag("identity_sync_$userId")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "identity_sync_$userId",
                policy,
                request
            )
        }
    }
}
