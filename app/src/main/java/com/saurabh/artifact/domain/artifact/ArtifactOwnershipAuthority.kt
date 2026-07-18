package com.saurabh.artifact.domain.artifact

import com.saurabh.artifact.repository.ArtifactRepository
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
    private val userRepository: UserRepository
) {
    /**
     * Checks if the current authenticated user is the owner of the given artifact.
     *
     * @param artifactId The ID of the artifact to check.
     * @return True if the current user is the owner, false otherwise.
     */
    suspend fun isCurrentUserOwner(artifactId: String): Boolean {
        val currentUserId = userRepository.getCurrentUserId() ?: return false
        val result = artifactRepository.getArtifactById(artifactId)
        
        return result.map { it.userId == currentUserId }.getOrDefault(false)
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
        val result = artifactRepository.getArtifactById(artifactId)
        
        return result.map { it.userId == userId }.getOrDefault(false)
    }
}
