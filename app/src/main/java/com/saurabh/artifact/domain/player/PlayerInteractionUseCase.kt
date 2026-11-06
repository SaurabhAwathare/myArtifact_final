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
        wasResonating: Boolean,
        expectedBaseGeneration: Long? = null
    ): Result<Unit> {
        val expectedGen = if (expectedBaseGeneration != null) {
            expectedBaseGeneration
        } else {
            val genResult = userRepository.fetchAuthoritativeRelationshipGeneration(currentUserId, targetUserId)
            genResult.getOrElse { return Result.failure(it) }
        }

        return if (wasResonating) {
            userRepository.stopResonatingWithUser(currentUserId, targetUserId, expectedBaseGeneration = expectedGen)
        } else {
            userRepository.resonateWithUser(currentUserId, targetUserId, expectedBaseGeneration = expectedGen)
        }
    }

    fun toggleSave(artifact: Artifact) {
        savedArtifactManager.toggleSave(artifact)
    }
}
