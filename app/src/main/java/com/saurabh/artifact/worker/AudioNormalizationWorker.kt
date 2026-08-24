package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.*
import com.saurabh.artifact.model.ProcessingStage
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@HiltWorker
class AudioNormalizationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftDao: Lazy<DraftDao>,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val startupCoordinator: com.saurabh.artifact.startup.StartupCoordinator
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // WORKER LOCK: Ensure database encryption is ready before proceeding
        startupCoordinator.awaitComponent(com.saurabh.artifact.startup.StartupComponent.DATABASE)

        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        val userId = authRepository.currentUserId
        
        if (userId.isEmpty()) return@withContext Result.failure()

        try {
            updateSubState(draftId, userId, ProcessingStage.NORMALIZING)
            
            // Simulation of audio normalization
            delay(2.seconds)
            
            Result.success()
        } catch (e: Exception) {
            updateSubState(draftId, userId, null, "Normalization failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun updateSubState(id: String, userId: String, stage: ProcessingStage?, error: String? = null) {
        val newProcessing = when {
            error != null -> ProcessingStatus.Failed
            stage != null -> ProcessingStatus.Active(stage)
            else -> ProcessingStatus.Idle
        }
        draftDao.get().updateProcessingStatus(id, userId, newProcessing)
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
