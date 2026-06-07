package com.saurabh.artifact.domain.feed

import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.model.ReactionVisibilityMode
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.ReactionRepository
import javax.inject.Inject

class ReactionUseCase @Inject constructor(
    private val reactionRepository: ReactionRepository,
    private val artifactRepository: ArtifactRepository,
) {
    suspend fun toggleReaction(
        artifactId: String,
        userId: String,
        type: ReactionType
    ): Result<Artifact?> {
        return reactionRepository.toggleReaction(artifactId, userId, type).map {
            artifactRepository.getArtifactById(artifactId)
        }
    }

    @Suppress("unused")
    suspend fun setVisibilityMode(
        artifactId: String,
        mode: ReactionVisibilityMode
    ): Result<Unit> {
        return reactionRepository.setVisibilityMode(artifactId, mode)
    }
}
