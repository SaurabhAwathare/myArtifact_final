package com.saurabh.artifact.domain

import com.saurabh.artifact.model.PublishingResult
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.domain.review.publishing.PublishingReviewPolicy
import android.util.Log
import javax.inject.Inject

class PublishArtifactUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val publishingOrchestrator: PublishingOrchestrator,
    private val publishingPolicy: PublishingReviewPolicy
) {
    suspend operator fun invoke(draftFilePath: String): Result<PublishingResult> {
        val draftResult = recordingRepository.getDraftByPath(draftFilePath)
        val draft = draftResult.getOrNull() ?: return Result.failure(Exception("Draft not found"))

        if (draft.lifecycle != com.saurabh.artifact.model.ArtifactLifecycle.READY_TO_PUBLISH) {
            Log.w("PublishValidation", "Draft ${draft.id} status: ${draft.lifecycle}, Progress: ${draft.reviewProgress}")
            val requiredPercent = (publishingPolicy.minCoverage * 100).toInt()
            return Result.failure(Exception("$requiredPercent% Review required before publishing"))
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
