package com.saurabh.artifact.ui.drafts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.domain.PublishArtifactUseCase
import com.saurabh.artifact.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DraftEditViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val publishArtifactUseCase: PublishArtifactUseCase,
    private val audioPlayer: com.saurabh.artifact.audio.AudioPlayer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DraftEditUiState())
    val uiState: StateFlow<DraftEditUiState> = combine(
        _uiState,
        audioPlayer.isPlaying,
        audioPlayer.currentPosition,
        audioPlayer.duration,
        audioPlayer.currentArtifact
    ) { state, isPlaying, position, duration, currentArtifact ->
        val draft = state.draft
        val isCurrentDraft = currentArtifact?.id == draft?.id
        val effectiveDuration = if (duration > 0) duration else draft?.durationMs ?: 0L
        val progress = if (effectiveDuration > 0) position.toFloat() / effectiveDuration else 0f
        
        // Update max review position only during active playback of THIS draft
        if (isCurrentDraft && isPlaying && (position > state.maxReviewPositionMs)) {
            val delta = position - state.maxReviewPositionMs
            // Only count progress if it's incremental (prevents skipping to the end)
            if (delta in 1..5000) { 
                updateMaxReviewPosition(position)
            }
        }

        // Persist current position periodically during playback
        if (isCurrentDraft && isPlaying && position != state.lastPlaybackPositionMs) {
            updateLastPlaybackPosition(position)
        }

        val reviewedPercentage = if (effectiveDuration > 0) state.maxReviewPositionMs.toFloat() / effectiveDuration else 0f
        val isListenedEnough = reviewedPercentage >= 0.95f || draft?.draftState == com.saurabh.artifact.model.ArtifactDraftState.PUBLISHED

        // Transition draft state to REVIEWED if threshold met
        if (isListenedEnough && draft?.draftState == com.saurabh.artifact.model.ArtifactDraftState.READY_TO_REVIEW) {
            markAsReviewed()
        }

        state.copy(
            isPlaying = if (isCurrentDraft) isPlaying else false,
            currentPosition = if (isCurrentDraft) position else 0L,
            duration = effectiveDuration,
            playbackProgress = if (isCurrentDraft) progress else 0f,
            isListenedEnough = isListenedEnough,
            reviewedPercentage = reviewedPercentage,
            draftState = draft?.draftState ?: com.saurabh.artifact.model.ArtifactDraftState.READY_TO_REVIEW,
            uploadedBytes = draft?.uploadedBytes ?: 0L,
            totalBytes = draft?.totalBytes ?: 0L
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DraftEditUiState()
    )

    private val _events = MutableSharedFlow<DraftEditEvent>()
    val events: SharedFlow<DraftEditEvent> = _events.asSharedFlow()

    private var autoSaveJob: Job? = null

    init {
        // Handle explicit completion to ensure 100% review even if polling lags
        viewModelScope.launch {
            audioPlayer.onPlaybackCompleted.collect { completedUrl ->
                val currentDraft = _uiState.value.draft
                if (currentDraft?.localAudioPath == completedUrl) {
                    val duration = _uiState.value.duration
                    if (duration > 0) {
                        updateMaxReviewPosition(duration)
                    }
                }
            }
        }
    }

    fun loadDraft(path: String) {
        viewModelScope.launch {
            val draft = recordingRepository.getDraftByPath(path)
            if (draft != null) {
                // Initial state
                _uiState.update { it.copy(
                    draft = draft,
                    title = draft.title ?: "",
                    isPublic = true,
                    selectedEmotion = draft.emotion ?: "",
                    reactionVisibility = draft.reactionVisibility ?: com.saurabh.artifact.model.ReactionVisibilityMode.APPROXIMATE,
                    maxReviewPositionMs = draft.maxReviewPositionMs,
                    lastPlaybackPositionMs = draft.lastPlaybackPositionMs
                ) }

                // Observe updates
                recordingRepository.observeDraft(draft.id).collect { updatedDraft ->
                    if (updatedDraft != null) {
                        _uiState.update { it.copy(draft = updatedDraft) }
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        val draft = _uiState.value.draft ?: return
        if (audioPlayer.currentArtifact.value?.id != draft.id) {
            // Convert draft to Artifact for player
            val artifact = com.saurabh.artifact.model.Artifact(
                id = draft.id,
                title = draft.title ?: "Draft",
                username = "You",
                audioUrl = draft.localAudioPath,
                emotion = draft.emotion ?: "neutral",
                amplitudeData = draft.amplitudeData,
                isDraft = true
            )
            // Resume from last position
            audioPlayer.play(artifact, initialPosition = _uiState.value.lastPlaybackPositionMs)
        } else {
            audioPlayer.togglePlayPause()
        }
    }

    fun seekTo(progress: Float) {
        val duration = uiState.value.duration
        if (duration > 0) {
            audioPlayer.seekTo((progress * duration).toLong())
        }
    }

    private fun updateMaxReviewPosition(position: Long) {
        val draftId = _uiState.value.draft?.id ?: return
        _uiState.update { it.copy(maxReviewPositionMs = position) }
        viewModelScope.launch {
            recordingRepository.updateReviewProgress(draftId, position)
        }
    }

    private fun updateLastPlaybackPosition(position: Long) {
        val draftId = _uiState.value.draft?.id ?: return
        _uiState.update { it.copy(lastPlaybackPositionMs = position) }
        viewModelScope.launch {
            recordingRepository.updateLastPlaybackPosition(draftId, position)
        }
    }

    private fun markAsReviewed() {
        val currentDraft = _uiState.value.draft ?: return
        val updatedDraft = currentDraft.copy(draftState = com.saurabh.artifact.model.ArtifactDraftState.REVIEWED)
        _uiState.update { it.copy(draft = updatedDraft) }
        viewModelScope.launch {
            recordingRepository.updateDraft(updatedDraft)
        }
    }

    fun updateTitle(title: String) {
        _uiState.update { it.copy(title = title) }
        scheduleAutoSave()
    }

    fun updateEmotion(emotion: String) {
        _uiState.update { it.copy(selectedEmotion = emotion) }
        scheduleAutoSave()
    }

    fun updateVisibility(isPublic: Boolean) {
        _uiState.update { it.copy(isPublic = isPublic) }
        scheduleAutoSave()
    }

    fun updateReactionVisibility(mode: com.saurabh.artifact.model.ReactionVisibilityMode) {
        _uiState.update { it.copy(reactionVisibility = mode) }
        scheduleAutoSave()
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(2000)
            saveDraftMetadata()
        }
    }

    private suspend fun saveDraftMetadata() {
        val currentDraft = _uiState.value.draft ?: return
        val updatedDraft = currentDraft.copy(
            title = _uiState.value.title,
            emotion = _uiState.value.selectedEmotion,
            reactionVisibility = _uiState.value.reactionVisibility
        )
        recordingRepository.updateDraft(updatedDraft)
        _uiState.update { it.copy(draft = updatedDraft) }
    }

    fun publish() {
        if (uiState.value.title.isBlank() || uiState.value.selectedEmotion.isBlank()) return

        viewModelScope.launch {
            saveDraftMetadata() // Ensure latest changes are saved
            
            val draft = _uiState.value.draft ?: return@launch
            _uiState.update { it.copy(isPublishing = true) }

            publishArtifactUseCase(draft.localAudioPath)
                .onSuccess {
                    _uiState.update { it.copy(isPublishing = false) }
                    _events.emit(DraftEditEvent.Published)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isPublishing = false) }
                    _events.emit(DraftEditEvent.Error(e.message ?: "Failed to publish"))
                }
        }
    }

    fun deleteDraft() {
        viewModelScope.launch {
            _uiState.value.draft?.let {
                audioPlayer.stop()
                recordingRepository.deleteDraft(it)
                _events.emit(DraftEditEvent.Deleted)
            }
        }
    }
}

data class DraftEditUiState(
    val draft: ArtifactDraftEntity? = null,
    val title: String = "",
    val isPublic: Boolean = true,
    val selectedEmotion: String = "",
    val reactionVisibility: com.saurabh.artifact.model.ReactionVisibilityMode = com.saurabh.artifact.model.ReactionVisibilityMode.APPROXIMATE,
    val isPublishing: Boolean = false,
    val draftState: com.saurabh.artifact.model.ArtifactDraftState = com.saurabh.artifact.model.ArtifactDraftState.READY_TO_REVIEW,
    val uploadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    
    // Playback State
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val playbackProgress: Float = 0f,
    val maxReviewPositionMs: Long = 0,
    val lastPlaybackPositionMs: Long = 0,
    val isListenedEnough: Boolean = false,
    val reviewedPercentage: Float = 0f
) {
    val canPublish: Boolean
        get() = title.isNotBlank() && selectedEmotion.isNotBlank() && isListenedEnough
}

sealed class DraftEditEvent {
    object Published : DraftEditEvent()
    object Deleted : DraftEditEvent()
    data class Error(val message: String) : DraftEditEvent()
}
