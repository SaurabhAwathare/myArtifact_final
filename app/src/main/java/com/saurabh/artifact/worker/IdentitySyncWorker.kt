package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.saurabh.artifact.domain.auth.SessionConstants
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.UserRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class IdentitySyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val artifactRepository: ArtifactRepository,
    private val userRepository: UserRepository,
    private val startupCoordinator: com.saurabh.artifact.startup.StartupCoordinator,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // WORKER LOCK: Ensure database encryption is ready before proceeding
        startupCoordinator.awaitComponent(com.saurabh.artifact.startup.StartupComponent.DATABASE)

        val userId = inputData.getString(KEY_USER_ID) ?: return@withContext Result.failure()
        val version = inputData.getLong(KEY_VERSION, 0)
        diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "IDENTITY_SYNC_STARTED", mapOf(LogKeys.USER_ID to userId, "version" to version))
        
        try {
            // 1. Fetch the latest profile from authoritative local/remote SSOT
            val userProfileResult = userRepository.getOrCreateProfile()
            val user = userProfileResult.getOrNull()?.user ?: return@withContext Result.retry()

            val snapshot = AuthorSnapshot.fromUser(user)

            // 2. Sync Local Room Cache (Optimistic & Resilient)
            // This ensures the local feed reflects the new identity immediately.
            // HISTORICAL FIX: Remote propagation is now handled by the backend Cloud Function
            // to overcome Firestore security rule restrictions on the 'author' field.
            artifactRepository.updateLocalAuthorSnapshot(userId, snapshot, user.identityMetadata.identityResetVersion)

            diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "IDENTITY_SYNC_SUCCESS", mapOf(LogKeys.USER_ID to userId))
            Result.success()
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.WORKMANAGER, "IDENTITY_SYNC_FAILED", mapOf(LogKeys.USER_ID to userId), e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_USER_ID = "userId"
        const val KEY_VERSION = "version"

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
                .addTag(SessionConstants.TAG_USER_SESSION_WORK)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "identity_sync_$userId",
                policy,
                request
            )
        }
    }
}
