package com.saurabh.artifact.ui.comment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentSnapshot
import com.saurabh.artifact.domain.comment.AddCommentUseCase
import com.saurabh.artifact.domain.comment.DeleteCommentUseCase
import com.saurabh.artifact.domain.comment.GetCommentsUseCase
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.Comment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing artifact comments.
 * 
 * Orchestrates domain use cases and maintains [CommentUiState].
 * 
 * Assumes "artifactId" is passed via SavedStateHandle.
 */
@HiltViewModel
class CommentViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val getCommentsUseCase: GetCommentsUseCase,
    private val addCommentUseCase: AddCommentUseCase,
    private val deleteCommentUseCase: DeleteCommentUseCase
) : ViewModel() {

    private var artifactId: String = savedStateHandle.get<String>("artifactId") ?: ""

    private val _uiState = MutableStateFlow(CommentUiState())
    val uiState: StateFlow<CommentUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CommentUiEvent>()
    val events: SharedFlow<CommentUiEvent> = _events.asSharedFlow()

    private var lastVisibleCursor: DocumentSnapshot? = null

    init {
        if (artifactId.isNotEmpty()) {
            loadInitialComments()
        }
    }

    /**
     * Initializes the ViewModel with a specific artifactId.
     * This is useful when the ViewModel is not created via a navigation route
     * that already contains the artifactId.
     */
    fun initialize(id: String) {
        android.util.Log.d("CommentVM", "initialize: current=$artifactId, new=$id")
        if (id.isEmpty() || id == artifactId) {
            android.util.Log.d("CommentVM", "initialize: skipping (id empty or same)")
            return
        }
        
        artifactId = id
        loadInitialComments()
    }

    /**
     * Loads the first page of comments.
     */
    fun loadInitialComments() {
        android.util.Log.d("CommentVM", "loadInitialComments: artifactId=$artifactId, thread=${Thread.currentThread().name}")
        if (artifactId.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isInitialLoading = true, error = null) }
            
            getCommentsUseCase(artifactId)
                .onSuccess { paginatedComments ->
                    android.util.Log.d("CommentVM", "loadInitialComments success: count=${paginatedComments.comments.size}, artifactId=$artifactId")
                    lastVisibleCursor = paginatedComments.lastVisible
                    _uiState.update { 
                        it.copy(
                            comments = paginatedComments.comments,
                            isInitialLoading = false,
                            hasMorePages = paginatedComments.lastVisible != null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { 
                        it.copy(
                            isInitialLoading = false,
                            error = AppError.from(error)
                        )
                    }
                }
        }
    }

    /**
     * Loads the next page of comments for pagination.
     */
    fun loadNextPage() {
        android.util.Log.d("CommentVM", "loadNextPage: artifactId=$artifactId, hasMore=${_uiState.value.hasMorePages}")
        if (artifactId.isEmpty() || 
            !_uiState.value.hasMorePages || 
            _uiState.value.isLoadingNextPage || 
            _uiState.value.isInitialLoading || 
            lastVisibleCursor == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingNextPage = true) }

            getCommentsUseCase(artifactId, lastVisible = lastVisibleCursor)
                .onSuccess { paginatedComments ->
                    android.util.Log.d("CommentVM", "loadNextPage success: count=${paginatedComments.comments.size}, artifactId=$artifactId")
                    lastVisibleCursor = paginatedComments.lastVisible
                    _uiState.update { 
                        it.copy(
                            comments = (it.comments + paginatedComments.comments).distinctBy { comment -> comment.id },
                            isLoadingNextPage = false,
                            hasMorePages = paginatedComments.lastVisible != null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { 
                        it.copy(
                            isLoadingNextPage = false,
                            error = AppError.from(error)
                        )
                    }
                }
        }
    }

    /**
     * Refreshes the comment list from the beginning.
     */
    fun refreshComments() {
        android.util.Log.d("CommentVM", "refreshComments: artifactId=$artifactId")
        if (artifactId.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }

            getCommentsUseCase(artifactId)
                .onSuccess { paginatedComments ->
                    android.util.Log.d("CommentVM", "refreshComments success: count=${paginatedComments.comments.size}, artifactId=$artifactId")
                    lastVisibleCursor = paginatedComments.lastVisible
                    _uiState.update { 
                        it.copy(
                            comments = paginatedComments.comments,
                            isRefreshing = false,
                            hasMorePages = paginatedComments.lastVisible != null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { 
                        it.copy(
                            isRefreshing = false,
                            error = AppError.from(error)
                        )
                    }
                }
        }
    }

    /**
     * Submits a new comment.
     * 
     * @param text The comment text to submit.
     */
    fun submitComment(text: String) {
        android.util.Log.d("CommentVM", "submitComment: text=$text, artifactId=$artifactId")
        if (artifactId.isEmpty() || _uiState.value.isSubmitting) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, submissionError = null) }

            addCommentUseCase(artifactId, text)
                .onSuccess { newComment ->
                    android.util.Log.d("CommentVM", "submitComment success: id=${newComment.id}, artifactId=$artifactId")
                    // Opting for refreshing or merging? 
                    // To ensure consistency with Firestore ordering, merging at the top for immediate feedback
                    _uiState.update { 
                        it.copy(
                            comments = (listOf(newComment) + it.comments).distinctBy { comment -> comment.id },
                            isSubmitting = false
                        )
                    }
                    _events.emit(CommentUiEvent.CommentSubmitted)
                }
                .onFailure { error ->
                    _uiState.update { 
                        it.copy(
                            isSubmitting = false,
                            submissionError = AppError.from(error)
                        )
                    }
                }
        }
    }

    /**
     * Deletes an existing comment.
     * 
     * @param comment The comment to delete.
     */
    fun deleteComment(comment: Comment) {
        viewModelScope.launch {
            deleteCommentUseCase(comment)
                .onSuccess {
                    _uiState.update { currentState ->
                        currentState.copy(
                            comments = currentState.comments.filter { it.id != comment.id }
                        )
                    }
                    _events.emit(CommentUiEvent.CommentDeleted)
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = AppError.from(error)) }
                }
        }
    }

    /**
     * Clears any active error from the UI state.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clears any active submission error from the UI state.
     */
    fun clearSubmissionError() {
        _uiState.update { it.copy(submissionError = null) }
    }
}
