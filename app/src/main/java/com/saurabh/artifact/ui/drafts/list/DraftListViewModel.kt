package com.saurabh.artifact.ui.drafts.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.AudioPlayer
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.domain.PublishingOrchestrator
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
    private val publishingOrchestrator: PublishingOrchestrator,
    val audioPlayer: AudioPlayer
) : ViewModel() {

    val drafts: StateFlow<List<ArtifactDraftEntity>> = recordingRepository.observeDrafts()
        .map { list -> 
            list.filter { 
                it.status.lifecycle != com.saurabh.artifact.model.ArtifactLifecycle.PUBLISHED &&
                it.status.lifecycle != com.saurabh.artifact.model.ArtifactLifecycle.READY_TO_PUBLISH
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val publishingDrafts: StateFlow<List<ArtifactDraftEntity>> = recordingRepository.observeDrafts()
        .map { list -> 
            list.filter { 
                it.status.lifecycle == com.saurabh.artifact.model.ArtifactLifecycle.READY_TO_PUBLISH 
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isPlaying = audioPlayer.isPlaying
    val currentlyPlayingArtifact = audioPlayer.currentArtifact

    fun playDraft(draft: ArtifactDraftEntity) {
        if (draft.status.sync is com.saurabh.artifact.model.SyncStatus.Recovering || 
            draft.syncState == com.saurabh.artifact.model.SyncState.RECOVERING) {
            // If recovering, we should first try to process it before playing
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

    fun retryPublish(draft: ArtifactDraftEntity) {
        viewModelScope.launch {
            publishingOrchestrator.retryPublishing(draft.id)
        }
    }

    fun cancelPublish(draft: ArtifactDraftEntity) {
        viewModelScope.launch {
            // Move back to review required and cancel work
            recordingRepository.updateDraft(draft.copy(
                status = draft.status.copy(
                    lifecycle = com.saurabh.artifact.model.ArtifactLifecycle.REVIEW_REQUIRED,
                    sync = com.saurabh.artifact.model.SyncStatus.LocalOnly
                )
            ))
            // WorkManager will handle the cancellation via tag if we implement it, 
            // but for now updating lifecycle removes it from the publishing queue view.
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
    }
}
