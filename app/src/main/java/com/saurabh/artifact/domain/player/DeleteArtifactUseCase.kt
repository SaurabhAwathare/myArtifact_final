package com.saurabh.artifact.domain.player

import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.RecordingRepository
import javax.inject.Inject

/**
 * Encapsulates the logic for deleting an artifact, whether it's a draft or published.
 */
class DeleteArtifactUseCase @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val recordingRepository: RecordingRepository
) {
    suspend fun execute(artifact: Artifact): Result<Unit> {
        return if (artifact.isDraft) {
            val draft = recordingRepository.getDraft(artifact.id)
            if (draft != null) {
                recordingRepository.deleteDraft(draft)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Draft not found"))
            }
        } else {
            artifactRepository.deletePublishedArtifact(artifact.id)
        }
    }
}
