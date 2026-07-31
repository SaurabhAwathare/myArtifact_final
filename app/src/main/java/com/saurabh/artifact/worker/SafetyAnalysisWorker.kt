package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DEPRECATED/INACTIVE: This worker was originally used for transcript-based safety evaluation.
 * Automatic transcription is currently disabled, making this worker redundant.
 * 
 * Scheduled for removal or repurposing for audio-based safety evaluation.
 */
@HiltWorker
class SafetyAnalysisWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftDao: DraftDao,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        val userId = authRepository.currentUserId
        
        if (userId.isEmpty()) return@withContext Result.failure()

        try {
            // Immediately transition to Idle as the feature is legacy
            draftDao.updateProcessingStatus(draftId, userId, ProcessingStatus.Idle)
            Result.success()
        } catch (_: Exception) {
            Result.failure()
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
