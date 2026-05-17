package com.saurabh.artifact.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.ArtifactReactionCounts
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.model.ReactionVisibilityMode
import com.saurabh.artifact.repository.ReactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReactionUiState(
    val counts: ArtifactReactionCounts? = null,
    val userReaction: ReactionType? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ReactionViewModel @Inject constructor(
    private val repository: ReactionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReactionUiState())
    val uiState: StateFlow<ReactionUiState> = _uiState.asStateFlow()

    private var currentArtifactId: String? = null

    fun loadReactions(artifactId: String) {
        currentArtifactId = artifactId
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            repository.getReactionCounts(artifactId)
                .collectLatest { counts ->
                    _uiState.update { 
                        it.copy(
                            counts = counts,
                            isLoading = false 
                        )
                    }
                }
        }
    }

    fun onReact(type: ReactionType, userId: String) {
        val artifactId = currentArtifactId ?: return
        
        // Optimistic Update
        val previousState = _uiState.value
        _uiState.update { state ->
            val updatedCounts = state.counts?.let { c ->
                val newBreakdown = c.breakdown.toMutableMap()
                newBreakdown[type.name] = (newBreakdown[type.name] ?: 0) + 1
                c.copy(
                    totalCount = c.totalCount + 1,
                    breakdown = newBreakdown
                )
            } ?: ArtifactReactionCounts(
                artifactId = artifactId,
                totalCount = 1,
                breakdown = mapOf(type.name to 1)
            )
            
            state.copy(
                userReaction = type,
                counts = updatedCounts
            )
        }

        viewModelScope.launch {
            repository.reactToArtifact(artifactId, userId, type).onFailure { e ->
                // Rollback on failure
                _uiState.value = previousState
                _uiState.update { it.copy(error = "Could not share resonance. Please try again.") }
            }
        }
    }

    fun updateVisibility(mode: ReactionVisibilityMode) {
        val artifactId = currentArtifactId ?: return
        viewModelScope.launch {
            repository.setVisibilityMode(artifactId, mode).onFailure { e ->
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
}
