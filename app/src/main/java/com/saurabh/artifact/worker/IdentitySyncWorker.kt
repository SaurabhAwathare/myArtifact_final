package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
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

            val name = user.anonymousName
            val sigil = user.anonymousSigil
            val avatarSeed = user.avatarSeed
            val avatarColor = user.avatarColor
            val avatarConfig = user.avatarConfig

            val authorUpdate = mapOf(
                "author.name" to name,
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

            Log.i("IdentitySyncWorker", "Global identity synchronization completed for $userId")
            Result.success()
        } catch (e: Exception) {
            Log.e("IdentitySyncWorker", "Identity sync failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_USER_ID = "userId"
        private const val BATCH_LIMIT = 500

        fun enqueue(context: Context, userId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<IdentitySyncWorker>()
                .setInputData(workDataOf(KEY_USER_ID to userId))
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
