package com.saurabh.artifact.ui.drafts.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackType
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.DraftRepository
import com.saurabh.artifact.repository.DraftWithUpload
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
    private val draftRepository: DraftRepository,
    private val publishingOrchestrator: PublishingOrchestrator,
    val audioPlayer: PlaybackCoordinator
) : ViewModel() {

    val drafts: StateFlow<List<DraftWithUpload>> = draftRepository.observeActiveDraftsWithUploads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val publishingDrafts: StateFlow<List<DraftWithUpload>> = draftRepository.observePublishingDraftsWithUploads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isPlaying = audioPlayer.isPlaying
    val currentlyPlayingArtifact = audioPlayer.currentArtifact

    fun playDraft(draftWithUpload: DraftWithUpload) {
        val draft = draftWithUpload.draft
        if (draft.status.publication is com.saurabh.artifact.model.SyncStatus.Recovering || 
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
            audioPlayer.playDraftPreview(draftIdString)
        }
    }

    fun deleteDraft(draftWithUpload: DraftWithUpload) {
        val draft = draftWithUpload.draft
        viewModelScope.launch {
            if (audioPlayer.currentArtifact.value?.id == draft.id.toString()) {
                audioPlayer.stop()
            }
            draftRepository.deleteDraftCompletely(draft.id)
        }
    }

    fun retryPublish(draftWithUpload: DraftWithUpload) {
        viewModelScope.launch {
            publishingOrchestrator.retryPublishing(draftWithUpload.draft.id)
        }
    }

    fun cancelPublish(draftWithUpload: DraftWithUpload) {
        val draft = draftWithUpload.draft
        viewModelScope.launch {
            // Move back to review required and cancel work
            draftRepository.updateUploadStatus(draft.id, com.saurabh.artifact.model.SyncStatus.LocalOnly)
            recordingRepository.updateDraft(draft.copy(
                status = draft.status.copy(
                    lifecycle = com.saurabh.artifact.model.ArtifactLifecycle.REVIEW_REQUIRED,
                    publication = com.saurabh.artifact.model.SyncStatus.LocalOnly
                )
            ))
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Playback ownership is now handled by the Coordinator.
    }
}
