package com.saurabh.artifact.ui.comment

import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.Comment

/**
 * Immutable state representing the UI for the artifact comment system.
 * 
 * This state is consumed by the Compose UI layer.
 */
data class CommentUiState(
    val comments: List<Comment> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isLoadingNextPage: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: AppError? = null,
    val isSubmitting: Boolean = false,
    val submissionError: AppError? = null,
    val hasMorePages: Boolean = true
) {
    /**
     * Helper to determine if the list is empty and not currently loading for the first time.
     */
    val isEmpty: Boolean get() = comments.isEmpty() && !isInitialLoading
}
