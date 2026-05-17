package com.saurabh.artifact.ui.feed

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.DraftSessionManager
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.model.ArtifactComment
import com.saurabh.artifact.model.CommentVisibilityMode
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.CommentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class CommentRecordingState {
    IDLE, RECORDING, COMPLETED, FAILED
}

data class CommentUiState(
    val recordingState: CommentRecordingState = CommentRecordingState.IDLE,
    val durationSeconds: Long = 0,
    val recordedFile: File? = null,
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
    private val draftSessionManager: DraftSessionManager,
    private val repository: CommentRepository,
    private val auth: AuthRepository,
    private val commentUnlockRepository: com.saurabh.artifact.repository.CommentUnlockRepository,
    private val listeningProgressTracker: com.saurabh.artifact.audio.ListeningProgressTracker
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommentUiState())
    val uiState: StateFlow<CommentUiState> = _uiState.asStateFlow()

    fun startRecording() {
        if (_uiState.value.recordingState == CommentRecordingState.RECORDING) return

        viewModelScope.launch {
            draftSessionManager.startNewSession(isComment = true)
        }
        _uiState.update { it.copy(recordingState = CommentRecordingState.RECORDING, durationSeconds = 0) }
    }

    fun stopRecording() {
        if (_uiState.value.recordingState != CommentRecordingState.RECORDING) return

        draftSessionManager.stopSession()
        // State update will happen via the Service observation in the UI layer
    }

    fun setRecordingState(status: RecordingStatus, duration: Long, file: File?) {
        _uiState.update { 
            val newState = when (status) {
                RecordingStatus.RECORDING -> CommentRecordingState.RECORDING
                RecordingStatus.COMPLETED -> CommentRecordingState.COMPLETED
                RecordingStatus.FAILED -> CommentRecordingState.FAILED
                else -> it.recordingState
            }
            it.copy(
                recordingState = newState,
                durationSeconds = duration,
                recordedFile = if (status == RecordingStatus.COMPLETED) file else it.recordedFile
            )
        }
    }

    fun reset() {
        _uiState.value = CommentUiState()
    }

    /**
     * Submits the recorded audio as a private reflection.
     */
    fun submitReflection(artifactId: String, visibility: CommentVisibilityMode, isAnonymous: Boolean) {
        val file = _uiState.value.recordedFile ?: return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            
            val result = repository.submitReflection(
                artifactId = artifactId,
                userId = auth.currentUserId,
                content = "", // Voice-first
                audioFilePath = file.absolutePath,
                visibility = visibility,
                isAnonymous = isAnonymous
            )

            if (result.isSuccess) {
                _uiState.update { it.copy(isSubmitting = false, submissionSuccess = true) }
                file.delete()
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
            val sessionFlow = listeningProgressTracker.sessionState
                .onStart { android.util.Log.d("ReviewDebug", "sessionFlow started") }

            combine(
                commentsFlow,
                unlockFlow,
                sessionFlow
            ) { comments, isUnlocked, session ->
                android.util.Log.d("ReviewDebug", "Flow emitted: repoIsUnlocked=$isUnlocked, sessionArtifactId=${session?.artifactId}")
                
                val isThresholdMetForThisArtifact = session?.artifactId == artifactId && session.isThresholdMet
                
                // Persistence side-effect
                if (isThresholdMetForThisArtifact && !isUnlocked) {
                    android.util.Log.d("ReviewDebug", "Persisting unlock for $artifactId")
                    viewModelScope.launch {
                        commentUnlockRepository.unlockArtifact(artifactId)
                    }
                }

                val finalIsUnlocked = isUnlocked || isThresholdMetForThisArtifact

                object {
                    val comments = comments
                    val isUnlocked = finalIsUnlocked
                    val hasCompletedReview = isThresholdMetForThisArtifact
                    val progress = if (session?.artifactId == artifactId) session.progress else 0f
                }
            }.collect { update ->
                android.util.Log.d("ReviewDebug", "Updating UI State: isLocked=${!update.isUnlocked}")

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
