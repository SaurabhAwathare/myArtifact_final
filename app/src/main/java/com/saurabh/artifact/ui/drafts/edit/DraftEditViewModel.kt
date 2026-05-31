package com.saurabh.artifact.ui.drafts.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackSessionManager
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
    private val playbackSessionManager: PlaybackSessionManager
) : ViewModel() {

    private val _draft = MutableStateFlow<ArtifactDraftEntity?>(null)
    val draft: StateFlow<ArtifactDraftEntity?> = _draft.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _emotion = MutableStateFlow<Emotion?>(null)
    val emotion: StateFlow<Emotion?> = _emotion.asStateFlow()

    val isPlaying = playbackSessionManager.isPlaying
    val currentPosition = playbackSessionManager.currentPosition

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
        val currentPlaying = playbackSessionManager.currentArtifact.value
        
        if (currentPlaying?.id == currentDraft.id) {
            playbackSessionManager.togglePlayPause()
        } else {
            val artifact = Artifact(
                id = currentDraft.id,
                title = _title.value.ifBlank { "Draft Preview" },
                audioUrl = currentDraft.localAudioPath,
                author = AuthorSnapshot(name = "Local Draft"),
                isDraft = true,
                amplitudeData = currentDraft.amplitudeData
            )
            playbackSessionManager.play(artifact)
        }
    }

    fun deleteDraft(onDeleted: () -> Unit) {
        val currentDraft = _draft.value ?: return
        viewModelScope.launch {
            if (playbackSessionManager.currentArtifact.value?.id == currentDraft.id) {
                playbackSessionManager.stop()
            }
            recordingRepository.deleteDraft(currentDraft)
            onDeleted()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // We don't necessarily want to stop playback when leaving the edit screen 
        // if the user wants to keep listening, but usually for "Edit" screens it's safer.
        playbackSessionManager.stop()
    }
}
