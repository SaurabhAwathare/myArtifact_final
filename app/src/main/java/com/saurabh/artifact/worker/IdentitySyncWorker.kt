package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
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
    private val userRepository: UserRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val userId = inputData.getString(KEY_USER_ID) ?: return@withContext Result.failure()
        
        Log.i("IdentitySyncWorker", "Starting global identity synchronization for user: $userId")

        try {
            // 1. Fetch the latest profile from Firestore (Source of Truth)
            val userProfileResult = userRepository.getOrCreateProfile()
            val user = userProfileResult.getOrNull()?.user ?: return@withContext Result.retry()

            val workerVersion = inputData.getLong(KEY_VERSION, 0L)
            Log.i("IdentitySyncWorker", "Syncing identity version $workerVersion for user: $userId")

            val name = user.anonymousName
            val anonymousId = user.anonymousId
            val sigil = user.anonymousSigil
            val avatarSeed = user.avatarSeed
            val avatarColor = user.avatarColor
            val avatarConfig = user.avatarConfig

            val authorUpdate = mapOf(
                "author.name" to name,
                "author.anonymousId" to anonymousId,
                "author.sigil" to sigil,
                "author.avatarSeed" to avatarSeed,
                "author.avatarColor" to avatarColor,
                "author.avatarConfig" to avatarConfig
            )

            val commentAuthorUpdate = mapOf(
                "authorAnonymousName" to name,
                "authorAvatarSeed" to avatarSeed
                // Note: If sigil/config were added to ArtifactComment, they'd be updated here too
            )

            // 2. Sync Artifacts
            val artifactsQuery = firestore.collection("artifacts")
                .whereEqualTo("userId", userId)
                .get()
                .await()

            if (!artifactsQuery.isEmpty) {
                Log.d("IdentitySyncWorker", "Syncing ${artifactsQuery.size()} artifacts...")
                val batches = artifactsQuery.documents.chunked(BATCH_LIMIT)
                for (chunk in batches) {
                    val batch = firestore.batch()
                    for (doc in chunk) {
                        batch.update(doc.reference, authorUpdate)
                    }
                    batch.commit().await()
                }
            }

            // 3. Sync Comments
            val commentsQuery = firestore.collection("comments")
                .whereEqualTo("authorId", userId)
                .get()
                .await()

            if (!commentsQuery.isEmpty) {
                Log.d("IdentitySyncWorker", "Syncing ${commentsQuery.size()} comments...")
                val batches = commentsQuery.documents.chunked(BATCH_LIMIT)
                for (chunk in batches) {
                    val batch = firestore.batch()
                    for (doc in chunk) {
                        batch.update(doc.reference, commentAuthorUpdate)
                    }
                    batch.commit().await()
                }
            }

            Log.i("IdentitySyncWorker", "Global identity synchronization completed for $userId (Version: $workerVersion)")

            // 4. Monotonic Update of lastCompletedIdentityVersion
            if (workerVersion > 0) {
                val userRef = firestore.collection("users").document(userId)
                firestore.runTransaction { transaction ->
                    val snapshot = transaction[userRef]
                    val currentCompleted = snapshot.getLong("identityMetadata.lastCompletedIdentityVersion") ?: 0L
                    if (workerVersion > currentCompleted) {
                        transaction.update(userRef, mapOf(
                            "identityMetadata.lastCompletedIdentityVersion" to workerVersion,
                            "identityMetadata.resetCompletedAt" to FieldValue.serverTimestamp()
                        ))
                    }
                }.await()
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("IdentitySyncWorker", "Identity sync failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_USER_ID = "userId"
        const val KEY_VERSION = "version"
        private const val BATCH_LIMIT = 500

        fun enqueue(context: Context, userId: String, version: Long = 0) {
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
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
