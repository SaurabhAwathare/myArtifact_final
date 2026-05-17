package com.saurabh.artifact.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.ArtifactComment
import com.saurabh.artifact.model.EmotionalResponseSummary
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.repository.CommentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreatorInboxUiState(
    val reflections: List<ArtifactComment> = emptyList(),
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
    private val repository: CommentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreatorInboxUiState())
    val uiState: StateFlow<CreatorInboxUiState> = _uiState.asStateFlow()

    fun loadInbox(artifactId: String, userId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Fetch summary first (Insight-first design)
            val summary = repository.getEmotionalSummary(artifactId)
            
            // Listen to reflections (Owner can see all)
            repository.getComments(artifactId, userId, userId)
                .onEach { reflections ->
                    _uiState.update { 
                        it.copy(
                            reflections = reflections,
                            summary = summary,
                            isLoading = false
                        )
                    }
                }
                .catch { e ->
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
                .collect()
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
}
