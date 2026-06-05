package com.saurabh.artifact.domain.player

import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.repository.SavedArtifactManager
import com.saurabh.artifact.model.Artifact
import javax.inject.Inject

class PlayerInteractionUseCase @Inject constructor(
    private val userRepository: UserRepository,
    private val savedArtifactManager: SavedArtifactManager
) {
    suspend fun toggleResonanceConnection(
        currentUserId: String,
        targetUserId: String,
        wasResonating: Boolean
    ): Result<Unit> {
        return if (wasResonating) {
            userRepository.stopResonatingWithUser(currentUserId, targetUserId)
        } else {
            userRepository.resonateWithUser(currentUserId, targetUserId)
        }
    }

    fun toggleSave(artifact: Artifact) {
        savedArtifactManager.toggleSave(artifact)
    }
}
