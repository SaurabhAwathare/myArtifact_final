package com.saurabh.artifact.ui.feed

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.ArtifactComment
import com.saurabh.artifact.model.CommentVisibilityMode
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.CommentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CommentUiState(
    val isSubmitting: Boolean = false,
    val submissionSuccess: Boolean = false,
    val comments: List<ArtifactComment> = emptyList(),
    val isLocked: Boolean = true,
    val hasCompletedReview: Boolean = false,
    val listeningProgress: Float = 0f
)

@Suppress("unused")
@HiltViewModel
class CommentViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: CommentRepository,
    private val auth: AuthRepository,
    private val commentUnlockRepository: com.saurabh.artifact.repository.CommentUnlockRepository,
    private val reviewSessionManager: com.saurabh.artifact.audio.ReviewSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommentUiState())
    val uiState: StateFlow<CommentUiState> = _uiState.asStateFlow()

    fun reset() {
        _uiState.value = CommentUiState()
    }

    /**
     * Submits the recorded text reflection.
     */
    fun submitReflection(artifactId: String, content: String, visibility: CommentVisibilityMode, isAnonymous: Boolean) {
        if (content.isBlank()) return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            
            val result = repository.submitReflection(
                artifactId = artifactId,
                userId = auth.currentUserId,
                content = content,
                visibility = visibility,
                isAnonymous = isAnonymous
            )

            if (result.isSuccess) {
                _uiState.update { it.copy(isSubmitting = false, submissionSuccess = true) }
            } else {
                _uiState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    fun loadComments(artifactId: String, ownerId: String) {
        android.util.Log.d("ReviewDebug", "loadComments called for artifactId=$artifactId")
        viewModelScope.launch {
            val commentsFlow = repository.getComments(artifactId, auth.currentUserId, ownerId)
                .onStart { android.util.Log.d("ReviewDebug", "commentsFlow started") }
            val unlockFlow = commentUnlockRepository.isUnlocked(artifactId)
                .onStart { android.util.Log.d("ReviewDebug", "unlockFlow started") }
            val sessionFlow = reviewSessionManager.reviewProgress
                .onStart { android.util.Log.d("ReviewDebug", "sessionFlow started") }

            combine(
                commentsFlow,
                unlockFlow,
                sessionFlow
            ) { comments, isUnlocked, session ->
                android.util.Log.d("ReviewDebug", "Flow emitted: repoIsUnlocked=$isUnlocked, sessionArtifactId=${session.artifactId}")
                
                val isThresholdMetForThisArtifact = session.artifactId == artifactId && session.isThresholdMet
                
                // Persistence side-effect
                if (isThresholdMetForThisArtifact && !isUnlocked) {
                    android.util.Log.d("ReviewDebug", "Triggering unlock side-effect for $artifactId")
                    viewModelScope.launch {
                        commentUnlockRepository.unlockArtifact(artifactId)
                    }
                }

                val finalIsUnlocked = isUnlocked || isThresholdMetForThisArtifact

                android.util.Log.d("ReviewDebug", "Final calculated unlock state for UI: $finalIsUnlocked (repo=$isUnlocked, sessionMet=$isThresholdMetForThisArtifact)")

                object {
                    val comments = comments
                    val isUnlocked = finalIsUnlocked
                    val hasCompletedReview = isThresholdMetForThisArtifact
                    val progress = if (session.artifactId == artifactId) session.progress else 0f
                }
            }.collect { update ->
                android.util.Log.d("ReviewDebug", "Collecting update in UI State: isLocked=${!update.isUnlocked}, progress=${update.progress}")

                _uiState.update { 
                    it.copy(
                        comments = update.comments,
                        isLocked = !update.isUnlocked,
                        hasCompletedReview = update.hasCompletedReview,
                        listeningProgress = update.progress
                    )
                }
            }
        }
    }
}
