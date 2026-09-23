package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.*
import com.saurabh.artifact.model.ProcessingStage
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.startup.StartupComponent
import com.saurabh.artifact.startup.StartupCoordinator
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@HiltWorker
class AudioNormalizationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftDao: Lazy<DraftDao>,
    private val authRepository: AuthRepository,
    private val startupCoordinator: StartupCoordinator
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // WORKER LOCK: Ensure database encryption is ready before proceeding
        startupCoordinator.awaitComponent(StartupComponent.DATABASE)

        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        val userId = authRepository.currentUserId

        if (userId.isEmpty()) return@withContext Result.failure()

        try {
            val draft = draftDao.get().getDraftById(draftId, userId) ?: return@withContext Result.failure()

            val audioPath = draft.rawPcmPath ?: draft.localAudioPath
            if (audioPath.isEmpty()) {
                updateSubState(draftId, userId, null, "Audio path is missing")
                return@withContext Result.failure()
            }

            val audioFile = File(audioPath)
            if (!audioFile.exists() || audioFile.length() == 0L) {
                updateSubState(draftId, userId, null, "Audio file missing or empty: $audioPath")
                return@withContext Result.failure()
            }

            updateSubState(draftId, userId, ProcessingStage.NORMALIZING)

            // Simulation of audio normalization
            delay(2.seconds)

            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
