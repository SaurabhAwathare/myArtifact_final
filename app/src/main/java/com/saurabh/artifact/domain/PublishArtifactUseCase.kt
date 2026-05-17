package com.saurabh.artifact.domain

import com.saurabh.artifact.repository.RecordingRepository
import javax.inject.Inject

class PublishArtifactUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val publishingOrchestrator: PublishingOrchestrator
) {
    suspend operator fun invoke(draftFilePath: String): Result<Unit> {
        val drafts = recordingRepository.observeDrafts() // This is a Flow, not ideal for one-shot
        // Better: use draftDao directly or add getDraftByPath to repository
        
        val draft = recordingRepository.getDraftByPath(draftFilePath) 
            ?: return Result.failure(Exception("Draft not found"))

        if (draft.title.isNullOrBlank()) {
            return Result.failure(Exception("Title is required"))
        }

        return try {
            publishingOrchestrator.approvePublishing(draft.id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
