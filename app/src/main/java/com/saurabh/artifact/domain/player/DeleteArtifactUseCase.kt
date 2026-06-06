package com.saurabh.artifact.domain.player

import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.model.Artifact
import javax.inject.Inject

/**
 * Encapsulates the logic for deleting an artifact, whether it's a draft or published.
 */
class DeleteArtifactUseCase @Inject constructor(
    private val cleanupManager: ArtifactCleanupManager
) {
    suspend fun execute(artifact: Artifact): Result<Unit> {
        return if (artifact.isDraft) {
            cleanupManager.deleteDraft(artifact.id)
        } else {
            cleanupManager.deleteArtifact(artifact.id)
        }
    }
}
