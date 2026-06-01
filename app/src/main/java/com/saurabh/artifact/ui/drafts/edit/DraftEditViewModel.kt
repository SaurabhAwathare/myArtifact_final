package com.saurabh.artifact.ui.drafts.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackType
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DraftEditViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val playbackCoordinator: PlaybackCoordinator
) : ViewModel() {

    private val _draft = MutableStateFlow<ArtifactDraftEntity?>(null)
    val draft: StateFlow<ArtifactDraftEntity?> = _draft.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _emotion = MutableStateFlow<Emotion?>(null)
    val emotion: StateFlow<Emotion?> = _emotion.asStateFlow()

    val isPlaying = playbackCoordinator.isPlaying
    val currentPosition = playbackCoordinator.currentPosition

    fun loadDraft(draftId: String) {
        viewModelScope.launch {
            val draftEntity = recordingRepository.getDraft(draftId)
            _draft.value = draftEntity
            _title.value = draftEntity?.title ?: ""
            
            // Try matching by name or label
            _emotion.value = draftEntity?.emotion?.let { value ->
                Emotion.entries.find { it.name == value || it.label == value }
            }
        }
    }

    fun updateTitle(newTitle: String) {
        _title.value = newTitle
    }

    fun updateEmotion(newEmotion: Emotion?) {
        _emotion.value = newEmotion
    }

    fun saveChanges() {
        val currentDraft = _draft.value ?: return
        viewModelScope.launch {
            recordingRepository.updateDraftMetadata(
                id = currentDraft.id,
                title = _title.value,
                emotion = _emotion.value?.name
            )
        }
    }

    fun togglePlayback() {
        val currentDraft = _draft.value ?: return
        val currentPlaying = playbackCoordinator.currentArtifact.value
        
        if (currentPlaying?.id == currentDraft.id) {
            playbackCoordinator.togglePlayPause()
        } else {
            playbackCoordinator.playDraftPreview(currentDraft.id)
        }
    }

    fun deleteDraft(onDeleted: () -> Unit) {
        val currentDraft = _draft.value ?: return
        viewModelScope.launch {
            if (playbackCoordinator.currentArtifact.value?.id == currentDraft.id) {
                playbackCoordinator.stop()
            }
            recordingRepository.deleteDraft(currentDraft)
            onDeleted()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Stop draft preview playback when exiting the edit screen
        playbackCoordinator.requestStop(PlaybackType.DRAFT_PREVIEW)
    }
}
