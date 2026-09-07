package com.saurabh.artifact.domain.artifact

import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.UserRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single authoritative way to determine artifact ownership.
 *
 * This centralizes the logic to ensure consistency across the UI, ViewModels,
 * and background tasks.
 */
@Singleton
class ArtifactOwnershipAuthority @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository
) {
    /**
     * Checks if the current authenticated user is the owner of the given artifact.
     *
     * @param artifactId The ID of the artifact to check.
     * @return True if the current user is the owner, false otherwise.
     */
    suspend fun isCurrentUserOwner(artifactId: String): Boolean {
        val currentUserId = userRepository.getCurrentUserId() ?: return false
        val currentAnonId = authRepository.currentAnonymousId
        val result = artifactRepository.getArtifactById(artifactId)
        
        return result.map { artifact ->
            (artifact.userId.isNotEmpty() && artifact.userId == currentUserId) ||
            (artifact.author.anonymousId.isNotEmpty() && currentAnonId.isNotEmpty() && artifact.author.anonymousId == currentAnonId)
        }.getOrDefault(false)
    }

    /**
     * Checks if a specific user is the owner of the given artifact.
     *
     * @param artifactId The ID of the artifact to check.
     * @param userId The ID of the user to check against.
     * @return True if the user is the owner, false otherwise.
     */
    suspend fun isOwner(artifactId: String, userId: String): Boolean {
        if (userId.isBlank()) return false
        val currentUserId = userRepository.getCurrentUserId()
        val currentAnonId = authRepository.currentAnonymousId
        val result = artifactRepository.getArtifactById(artifactId)
        
        return result.map { artifact ->
            (artifact.userId.isNotEmpty() && artifact.userId == userId) ||
            (userId == currentUserId && artifact.author.anonymousId.isNotEmpty() && currentAnonId.isNotEmpty() && artifact.author.anonymousId == currentAnonId)
        }.getOrDefault(false)
    }
}
