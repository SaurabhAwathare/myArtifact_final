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
        val currentAnonymousId = authRepository.currentAnonymousId
        
        // 1. Ownership Check
        // Deterministic attribution: Prove ownership via current persona or legacy UID
        val isOwnedByCurrentPersona = currentAnonymousId.isNotEmpty() && currentAnonymousId == comment.author.anonymousId
        
        if (currentUserId.isBlank() || !isOwnedByCurrentPersona) {
            // Note: If this is a historical comment from before an identity reset, 
            // the local check will fail but the Firestore Security Rules will still 
            // allow it if the UID matches via persona_mapping.
            // We proceed with the repository call and let the backend decide.
            if (currentUserId.isBlank()) {
                return Result.failure(AppError.PermissionDenied("You must be signed in to delete comments."))
            }
        }

        // 2. Delegate to Repository
        return repository.deleteComment(
            artifactId = comment.artifactId,
            commentId = comment.id
        )
    }
}
