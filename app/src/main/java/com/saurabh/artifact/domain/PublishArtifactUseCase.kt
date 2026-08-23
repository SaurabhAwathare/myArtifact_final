package com.saurabh.artifact.domain

import com.saurabh.artifact.model.PublishingResult
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.domain.review.publishing.PublishingReviewPolicy
import android.util.Log
import javax.inject.Inject

class PublishArtifactUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val publishingOrchestrator: PublishingOrchestrator,
    private val publishingPolicy: PublishingReviewPolicy
) {
    suspend operator fun invoke(draftFilePath: String): Result<PublishingResult> {
        val userId = authRepository.currentUserId
        if (userId.isEmpty()) return Result.failure(com.saurabh.artifact.model.AppError.Unauthenticated())
        
        val draftResult = recordingRepository.getDraftByPath(draftFilePath)
        val draft = draftResult.getOrNull() ?: return Result.failure(com.saurabh.artifact.model.AppError.NotFound("Draft", draftFilePath))

        if (draft.lifecycle != com.saurabh.artifact.model.ArtifactLifecycle.READY_TO_PUBLISH) {
            Log.w("PublishValidation", "Draft status: ${draft.lifecycle}, Progress: ${draft.reviewProgress}")
            val requiredPercent = (publishingPolicy.minCoverage * 100).toInt()
            return Result.failure(com.saurabh.artifact.model.AppError.InvalidInput("$requiredPercent% Review required before publishing"))
        }

        if (draft.title.isNullOrBlank()) {
            return Result.failure(com.saurabh.artifact.model.AppError.InvalidInput("Title is required"))
        }

        if (draft.title.length > 70) {
            return Result.failure(com.saurabh.artifact.model.AppError.InvalidInput("Title must not exceed 70 characters"))
        }

        return try {
            publishingOrchestrator.approvePublishing(draft.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
