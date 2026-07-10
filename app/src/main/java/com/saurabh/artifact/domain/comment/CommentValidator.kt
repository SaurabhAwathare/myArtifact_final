package com.saurabh.artifact.domain.comment

import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.util.CommentConstants
import javax.inject.Inject

/**
 * Centralizes business rules and validation for comments.
 */
class CommentValidator @Inject constructor() {

    /**
     * Validates and cleans raw comment text.
     * 
     * Rules:
     * 1. Trim leading and trailing whitespace.
     * 2. Reject empty or blank comments.
     * 3. Enforce minimum length (CommentConstants.MIN_COMMENT_LENGTH).
     * 4. Enforce maximum length (CommentConstants.MAX_COMMENT_LENGTH).
     * 
     * @param rawText The unvalidated text from the UI.
     * @return A [Result] containing the trimmed, valid text or [AppError.InvalidInput].
     */
    fun validate(rawText: String?): Result<String> {
        val trimmedText = rawText?.trim() ?: ""

        if (trimmedText.isEmpty()) {
            return Result.failure(AppError.InvalidInput("Comment cannot be empty"))
        }

        if (trimmedText.length < CommentConstants.MIN_COMMENT_LENGTH) {
            return Result.failure(
                AppError.InvalidInput("Comment must be at least ${CommentConstants.MIN_COMMENT_LENGTH} character(s) long")
            )
        }

        if (trimmedText.length > CommentConstants.MAX_COMMENT_LENGTH) {
            return Result.failure(
                AppError.InvalidInput("Comment exceeds maximum length of ${CommentConstants.MAX_COMMENT_LENGTH} characters")
            )
        }

        return Result.success(trimmedText)
    }
}
