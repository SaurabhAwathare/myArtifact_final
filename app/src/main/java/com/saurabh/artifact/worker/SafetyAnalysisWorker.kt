package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.service.SafetyEvaluator
import com.saurabh.artifact.service.SafetyLevel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@HiltWorker
class SafetyAnalysisWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftDao: DraftDao,
    private val safetyEvaluator: SafetyEvaluator
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        
        try {
            updateSubState(draftId, com.saurabh.artifact.model.ProcessingStage.SAFETY_CHECK)
            
            val draft = draftDao.getDraftById(draftId) ?: return@withContext Result.failure()
            
            // Perform real safety evaluation on transcript
            val transcriptPath = draft.localTranscriptPath
            val safetyResult = if (transcriptPath != null) {
                val transcriptFile = File(transcriptPath)
                if (transcriptFile.exists()) {
                    val transcript = transcriptFile.readText()
                    safetyEvaluator.evaluate(transcript)
                } else {
                    null
                }
            } else {
                null
            }

            // Simulation of additional AI processing if needed
            delay(500)
            
            draftDao.update(draft.copy(
                safetyAnalysis = safetyResult?.level?.name ?: "UNKNOWN",
                emotionalRiskScore = if (safetyResult?.level == SafetyLevel.HIGH) 1.0f else 0.0f,
                updatedAt = System.currentTimeMillis()
            ))
            
            Result.success()
        } catch (e: Exception) {
            updateSubState(draftId, null, "Safety analysis failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun updateSubState(id: String, stage: com.saurabh.artifact.model.ProcessingStage?, error: String? = null) {
        draftDao.getDraftById(id)?.let { draft ->
            val newProcessing = when {
                error != null -> com.saurabh.artifact.model.ProcessingStatus.Failed(error)
                stage != null -> com.saurabh.artifact.model.ProcessingStatus.Active(stage)
                else -> com.saurabh.artifact.model.ProcessingStatus.Idle
            }
            draftDao.update(draft.copy(
                status = draft.status.copy(processing = newProcessing),
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
