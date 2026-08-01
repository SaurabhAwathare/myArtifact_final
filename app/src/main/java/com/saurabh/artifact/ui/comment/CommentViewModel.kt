package com.saurabh.artifact.ui.comment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentSnapshot
import com.saurabh.artifact.domain.artifact.ArtifactOwnershipAuthority
import com.saurabh.artifact.domain.comment.AddCommentUseCase
import com.saurabh.artifact.domain.comment.DeleteCommentUseCase
import com.saurabh.artifact.domain.comment.GetCommentsUseCase
import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.repository.EngagementRepository
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.Comment
import com.saurabh.artifact.model.SyncState
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.LogKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
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
    private val deleteCommentUseCase: DeleteCommentUseCase,
    private val engagementRepository: EngagementRepository,
    private val ownershipAuthority: ArtifactOwnershipAuthority,
    private val diagnosticLogger: DiagnosticLogger
) : ViewModel() {

    private var artifactId: String = savedStateHandle.get<String>("artifactId") ?: ""
    private var isOwner: Boolean = false

    private val _uiState = MutableStateFlow(CommentUiState())
    val uiState: StateFlow<CommentUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CommentUiEvent>()
    val events: SharedFlow<CommentUiEvent> = _events.asSharedFlow()

    private var lastVisibleCursor: DocumentSnapshot? = null

    private var unlockObservationJob: Job? = null

    init {
        if (artifactId.isNotEmpty()) {
            checkOwnershipAndInitialize()
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
        checkOwnershipAndInitialize()
    }

    private fun checkOwnershipAndInitialize() {
        viewModelScope.launch {
            isOwner = ownershipAuthority.isCurrentUserOwner(artifactId)
            android.util.Log.d("COMMENT_TRACE", "Ownership check: artifactId=$artifactId, isOwner=$isOwner")
            
            loadInitialComments()
            observeUnlockStatus()
        }
    }

    /**
     * Observes the backend authoritative unlock state and local sync progress.
     */
    private fun observeUnlockStatus() {
        if (artifactId.isEmpty()) return

        android.util.Log.d("COMMENT_TRACE", "Cancelling unlock observation for artifactId=$artifactId")
        unlockObservationJob?.cancel()

        android.util.Log.d("COMMENT_TRACE", "Starting unlock observation for artifactId=$artifactId")
        unlockObservationJob = viewModelScope.launch {
            engagementRepository.observeEngagementEvidence(artifactId)
                .collectLatest { evidence ->
                    val newState = deriveUnlockState(evidence)
                    android.util.Log.d("COMMENT_TRACE", "Unlock state update: artifactId=$artifactId, newState=$newState")
                    _uiState.update { it.copy(unlockState = newState) }

                    if (newState == CommentUnlockState.VERIFYING) {
                        // Phase 8: Reliability - Implement timeout for backend verification
                        delay(30_000) // 30 second timeout

                        if (_uiState.value.unlockState == CommentUnlockState.VERIFYING) {
                            diagnosticLogger.warn(
                                DiagnosticCategory.COMMENT,
                                "COMMENT_UNLOCK_TIMEOUT",
                                mapOf(
                                    LogKeys.ARTIFACT_ID to artifactId,
                                    "timeoutMs" to 30000
                                )
                            )
                            _uiState.update { it.copy(unlockState = CommentUnlockState.TIMEOUT) }
                        }
                    }
                }
        }
    }

    /**
     * Triggers a re-sync of engagement evidence after a timeout.
     */
    fun retryUnlock() {
        if (artifactId.isEmpty()) return
        
        viewModelScope.launch {
            diagnosticLogger.info(
                DiagnosticCategory.COMMENT,
                "COMMENT_UNLOCK_RETRY_CLICKED",
                mapOf(LogKeys.ARTIFACT_ID to artifactId)
            )
            // Reset to SYNCING locally to provide immediate feedback
            _uiState.update { it.copy(unlockState = CommentUnlockState.SYNCING) }
            engagementRepository.forceRetrySync(artifactId)
        }
    }

    /**
     * Derives the UI presentation state for comment unlocking.
     */
    private fun deriveUnlockState(evidence: EngagementEvidence?): CommentUnlockState {
        // 0. Owner Bypass: Owners are always unlocked
        if (isOwner) {
            return CommentUnlockState.UNLOCKED
        }

        if (evidence == null) return CommentUnlockState.LOCKED

        // 1. Authoritative Unlock (Backend Says YES)
        if (evidence.unlockStatus.isCommentUnlocked) {
            return CommentUnlockState.UNLOCKED
        }

        // 2. Local Sync in Progress (Evidence moving to server)
        if (evidence.syncState == SyncState.PENDING || evidence.syncState == SyncState.SYNCING) {
            return CommentUnlockState.SYNCING
        }

        // 3. Post-Sync Verification
        // If we just synced (SYNCED) but backend is still false.
        if (evidence.syncState == SyncState.SYNCED && !evidence.unlockStatus.isCommentUnlocked) {
            // We show VERIFYING while we wait for the backend to process the latest evidence.
            // We trust the backend's UNLOCK_TIMESTAMP or UPDATED_AT to know if it has seen our latest sync.
            // Note: In a real app, we might use a small timeout here to transition back to LOCKED if no flip occurs.
            return CommentUnlockState.VERIFYING
        }

        // 4. Fallback/Failure
        return if (evidence.syncState == SyncState.FAILED) {
            CommentUnlockState.ERROR
        } else {
            CommentUnlockState.LOCKED
        }
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
                    val appError = AppError.from(error)
                    android.util.Log.e("CommentVM", "submitComment failed: artifactId=$artifactId, error=${appError.technicalMessage}", error)
                    
                    _uiState.update { 
                        it.copy(
                            isSubmitting = false,
                            submissionError = appError
                        )
                    }
                    _events.emit(CommentUiEvent.SubmissionFailed(appError.technicalMessage))
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
