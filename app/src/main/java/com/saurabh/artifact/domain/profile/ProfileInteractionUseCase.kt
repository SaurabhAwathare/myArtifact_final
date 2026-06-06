package com.saurabh.artifact.domain.profile

import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.repository.UserRepository
import javax.inject.Inject

class ProfileInteractionUseCase @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val recordingRepository: RecordingRepository,
    private val userRepository: UserRepository,
    private val cleanupManager: ArtifactCleanupManager
) {
    suspend fun toggleResonance(currentUserId: String, targetUserId: String, wasResonating: Boolean): Result<Unit> {
        return try {
            if (wasResonating) {
                userRepository.stopResonatingWithUser(currentUserId, targetUserId)
            } else {
                userRepository.resonateWithUser(currentUserId, targetUserId)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun renameDraft(draftId: String, newTitle: String) {
        recordingRepository.renameDraft(draftId, newTitle)
    }

    suspend fun deleteDraft(draftId: String) {
        cleanupManager.deleteDraft(draftId)
    }

    suspend fun renamePublishedArtifact(artifactId: String, newTitle: String): Result<Unit> {
        return artifactRepository.renamePublishedArtifact(artifactId, newTitle)
    }

    suspend fun deletePublishedArtifact(artifactId: String): Result<Unit> {
        return cleanupManager.deleteArtifact(artifactId)
    }
}
