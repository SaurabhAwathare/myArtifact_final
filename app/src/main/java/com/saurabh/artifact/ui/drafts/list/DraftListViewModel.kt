package com.saurabh.artifact.ui.drafts.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.AudioPlayer
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DraftListViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    val audioPlayer: AudioPlayer
) : ViewModel() {

    val drafts: StateFlow<List<ArtifactDraftEntity>> = recordingRepository.observeDrafts()
        .map { list -> 
            list.filter { it.draftState != com.saurabh.artifact.model.ArtifactDraftState.PUBLISHED }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isPlaying = audioPlayer.isPlaying
    val currentlyPlayingArtifact = audioPlayer.currentArtifact

    fun playDraft(draft: ArtifactDraftEntity) {
        if (draft.syncState == com.saurabh.artifact.model.SyncState.INTERRUPTED) {
            // If interrupted, we should first try to process it before playing?
            // Or just play the raw WAV. Let's play the raw WAV first so they can hear what was saved.
            // But we should also trigger processing in the background.
            viewModelScope.launch {
                recordingRepository.startProcessing(draft.id)
            }
        }

        val currentPlaying = audioPlayer.currentArtifact.value
        val draftIdString = draft.id.toString()
        if (currentPlaying?.id == draftIdString) {
            audioPlayer.togglePlayPause()
        } else {
            val artifact = Artifact(
                id = draftIdString,
                title = draft.title ?: "Untitled Reflection",
                audioUrl = draft.localAudioPath,
                author = com.saurabh.artifact.model.AuthorSnapshot(name = "Local Draft"),
                isDraft = true,
                amplitudeData = draft.amplitudeData
            )
            audioPlayer.play(artifact)
        }
    }

    fun deleteDraft(draft: ArtifactDraftEntity) {
        viewModelScope.launch {
            if (audioPlayer.currentArtifact.value?.id == draft.id.toString()) {
                audioPlayer.stop()
            }
            recordingRepository.deleteDraft(draft)
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
    }
}
