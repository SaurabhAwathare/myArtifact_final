package com.saurabh.artifact.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.saurabh.artifact.model.ArtifactComment
import com.saurabh.artifact.model.CommentModerationState
import com.saurabh.artifact.model.EmotionalResponseSummary
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.CommentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreatorInboxUiState(
    val summary: EmotionalResponseSummary? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * ViewModel for the "Hearth" view.
 * Provides a calm, insight-first summary of listener reflections.
 */
@HiltViewModel
class CreatorInboxViewModel @Inject constructor(
    private val repository: CommentRepository,
    private val auth: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreatorInboxUiState())
    val uiState: StateFlow<CreatorInboxUiState> = _uiState.asStateFlow()

    private val _artifactIdForPaging = MutableStateFlow<Pair<String, String>?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val reflectionsPager: Flow<PagingData<ArtifactComment>> = _artifactIdForPaging
        .flatMapLatest { pair ->
            if (pair == null) {
                flowOf(PagingData.empty())
            } else {
                // Owner can see all non-sanctuary comments, repository handles this
                repository.getCommentsPager(pair.first, auth.currentUserId, pair.second)
            }
        }
        .cachedIn(viewModelScope)

    fun loadInbox(artifactId: String, userId: String) {
        _artifactIdForPaging.value = artifactId to userId
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Fetch summary first (Insight-first design)
            val summary = repository.getEmotionalSummary(artifactId)
            
            _uiState.update { 
                it.copy(
                    summary = summary,
                    isLoading = false
                )
            }
        }
    }

    /**
     * Allows the creator to react to a specific listener reflection.
     */
    fun reactToComment(commentId: String, type: ReactionType) {
        viewModelScope.launch {
            repository.reactToComment(commentId, type).onFailure { e ->
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun approveComment(commentId: String) {
        viewModelScope.launch {
            repository.updateCommentModerationState(commentId, CommentModerationState.APPROVED)
        }
    }

    fun flagComment(commentId: String) {
        viewModelScope.launch {
            repository.updateCommentModerationState(commentId, CommentModerationState.FLAGGED)
        }
    }
}
