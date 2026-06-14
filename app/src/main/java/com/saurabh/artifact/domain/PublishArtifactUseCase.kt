package com.saurabh.artifact.domain

import com.saurabh.artifact.model.PublishingResult
import com.saurabh.artifact.repository.RecordingRepository
import android.util.Log
import javax.inject.Inject

class PublishArtifactUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val publishingOrchestrator: PublishingOrchestrator
) {
    suspend operator fun invoke(draftFilePath: String): Result<PublishingResult> {
        val draftResult = recordingRepository.getDraftByPath(draftFilePath)
        val draft = draftResult.getOrNull() ?: return Result.failure(Exception("Draft not found"))

        if (draft.status.lifecycle != com.saurabh.artifact.model.ArtifactLifecycle.READY_TO_PUBLISH) {
            Log.w("PublishValidation", "Draft ${draft.id} status: ${draft.status.lifecycle}, Progress: ${draft.reviewProgress}")
            return Result.failure(Exception("95% Review required before publishing"))
        }

        if (draft.title.isNullOrBlank()) {
            return Result.failure(Exception("Title is required"))
        }

        return try {
            val result = publishingOrchestrator.approvePublishing(draft.id)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
