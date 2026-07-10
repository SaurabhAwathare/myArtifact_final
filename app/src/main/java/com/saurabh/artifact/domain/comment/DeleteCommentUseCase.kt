package com.saurabh.artifact.domain.comment

import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.Comment
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.CommentRepository
import javax.inject.Inject

/**
 * Use case for deleting a comment.
 * 
 * Enforces ownership rules: only the creator can delete their comment.
 */
class DeleteCommentUseCase @Inject constructor(
    private val repository: CommentRepository,
    private val authRepository: AuthRepository
) {
    /**
     * Executes the delete comment flow.
     * 
     * @param comment The comment domain object to be deleted.
     * @return A [Result] indicating success or failure.
     */
    suspend operator fun invoke(comment: Comment): Result<Unit> {
        val currentUserId = authRepository.currentUserId
        
        // 1. Ownership Check
        if (currentUserId.isBlank() || currentUserId != comment.creatorId) {
            return Result.failure(AppError.PermissionDenied("You can only delete your own comments."))
        }

        // 2. Delegate to Repository
        return repository.deleteComment(
            artifactId = comment.artifactId,
            commentId = comment.id
        )
    }
}
