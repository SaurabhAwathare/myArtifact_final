package com.saurabh.artifact.domain.comment

import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.Comment
import com.saurabh.artifact.model.CommentStatus
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.CommentRepository
import com.google.firebase.Timestamp
import javax.inject.Inject

/**
 * Use case for adding a new comment to an artifact.
 * 
 * Coordinates validation, identity snapshotting, and persistence.
 */
class AddCommentUseCase @Inject constructor(
    private val commentRepository: CommentRepository,
    private val authRepository: AuthRepository,
    private val validator: CommentValidator
) {
    /**
     * Executes the add comment flow.
     * 
     * @param artifactId The ID of the artifact being commented on.
     * @param rawText The raw input text from the user.
     * @return A [Result] containing the created [Comment] or an [AppError].
     */
    suspend operator fun invoke(artifactId: String, rawText: String): Result<Comment> {
        // 1. Validate Input
        val validationResult = validator.validate(rawText)
        if (validationResult.isFailure) {
            return Result.failure(validationResult.exceptionOrNull()!!)
        }
        val validatedText = validationResult.getOrThrow()

        // 2. Resolve Identity
        val user = authRepository.userData.value
            ?: return Result.failure(AppError.Unauthenticated("User profile not found. Please sign in."))

        val authorSnapshot = AuthorSnapshot.fromUser(user)

        // 3. Construct Domain Object
        val now = Timestamp.now()
        val commentId = java.util.UUID.randomUUID().toString()
        val comment = Comment(
            id = commentId,
            artifactId = artifactId,
            creatorId = user.id,
            author = authorSnapshot,
            text = validatedText,
            createdAt = now,
            updatedAt = now,
            status = CommentStatus.ACTIVE,
            identityVersion = user.identityMetadata.identityResetVersion
        )

        // 4. Delegate to Repository
        return commentRepository.createComment(comment)
    }
}
