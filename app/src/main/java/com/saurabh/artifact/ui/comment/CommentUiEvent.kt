package com.saurabh.artifact.ui.comment

/**
 * One-time events for the comment UI that shouldn't be persisted in [CommentUiState].
 */
sealed class CommentUiEvent {
    /**
     * Emitted when a comment has been successfully submitted.
     * Can be used to clear input, hide keyboard, or scroll to the top.
     */
    object CommentSubmitted : CommentUiEvent()
    
    /**
     * Emitted when a comment has been successfully deleted.
     */
    object CommentDeleted : CommentUiEvent()
}
