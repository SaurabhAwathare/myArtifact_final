package com.saurabh.artifact.service

import com.google.firebase.Timestamp
import com.saurabh.artifact.model.ArtifactComment
import com.saurabh.artifact.model.VisibilityLayer
import com.saurabh.artifact.model.CommentModerationState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CORE LOGIC ENGINE: Hidden Comments Visibility
 * 
 * This class encapsulates the emotional-privacy rules of myArtifact.
 * It determines if a comment should be shown to a user based on their identity
 * and the comment's visibility settings.
 */
@Singleton
class CommentVisibilityManager @Inject constructor() {

    /**
     * Evaluates if a comment is visible to a viewer.
     * 
     * @param viewerId The ID of the user trying to view the comment.
     * @param artifactOwnerId The ID of the person who created the artifact.
     * @param comment The comment being evaluated.
     * @return True if the viewer is permitted to see the comment.
     */
    fun isVisible(
        viewerId: String,
        artifactOwnerId: String,
        comment: ArtifactComment
    ): Boolean {
        // 1. BLOCKED content is invisible to everyone except moderators (handled by DB)
        if (comment.moderationState == CommentModerationState.BLOCKED) return false

        // 2. The author can ALWAYS see their own reflection
        if (comment.authorId == viewerId) return true

        // 3. The artifact owner can ALWAYS see reflections on their own content
        // (This is the "Hearth" principle)
        if (artifactOwnerId == viewerId) return true

        // 4. Handle RESONANCE (Public) visibility
        if (comment.visibilityLayer == VisibilityLayer.RESONANCE) {
            // Must be approved for public view
            if (comment.moderationState != CommentModerationState.APPROVED) return false
            
            // Handle Reveal logic
            val now = Timestamp.now()
            return comment.revealAt == null || now.seconds >= comment.revealAt.seconds
        }

        // 5. Default: HIDDEN (BRIDGE between listener and creator, or SANCTUARY)
        return false
    }

    /**
     * Filters a list of comments for a specific viewer.
     */
    fun filterComments(
        viewerId: String,
        artifactOwnerId: String,
        comments: List<ArtifactComment>
    ): List<ArtifactComment> {
        return comments.filter { isVisible(viewerId, artifactOwnerId, it) }
    }
}
