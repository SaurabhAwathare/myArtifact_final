package com.saurabh.artifact.ui.drafts.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.repository.DraftRepository
import com.saurabh.artifact.repository.DraftWithUpload
import com.saurabh.artifact.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

sealed class DraftListUiEvent {
    data class NavigateToReview(val draftId: String) : DraftListUiEvent()
    data class NavigateToEdit(val draftId: String) : DraftListUiEvent()
    data class NavigateToPublish(val draftId: String) : DraftListUiEvent()
}

@HiltViewModel
class DraftListViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val draftRepository: DraftRepository,
    private val publishingOrchestrator: PublishingOrchestrator,
    private val cleanupManager: ArtifactCleanupManager,
    audioPlayer: PlaybackCoordinator
) : ViewModel() {

    val drafts: StateFlow<List<DraftWithUpload>> = draftRepository.observeActiveDraftsWithUploads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val publishingDrafts: StateFlow<List<DraftWithUpload>> = draftRepository.observePublishingDraftsWithUploads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isPlaying = audioPlayer.isPlaying

    private val _events = MutableSharedFlow<DraftListUiEvent>()
    val events = _events.asSharedFlow()

    fun onDraftClicked(draftWithUpload: DraftWithUpload) {
        val draft = draftWithUpload.draft
        if (draft.status.publication is com.saurabh.artifact.model.SyncStatus.Recovering) {
            // Trigger processing for interrupted draft
            viewModelScope.launch {
                publishingOrchestrator.startProcessing(draft.id)
            }
        } else {
            viewModelScope.launch {
                _events.emit(DraftListUiEvent.NavigateToReview(draft.id))
            }
        }
    }

    fun onEditClicked(draftId: String) {
        viewModelScope.launch {
            _events.emit(DraftListUiEvent.NavigateToEdit(draftId))
        }
    }

    fun onPublishClicked(draftId: String) {
        viewModelScope.launch {
            val draft = draftRepository.getDraft(draftId).getOrNull()
            if (draft?.lifecycle == com.saurabh.artifact.model.ArtifactLifecycle.READY_TO_PUBLISH) {
                _events.emit(DraftListUiEvent.NavigateToPublish(draftId))
            } else {
                _events.emit(DraftListUiEvent.NavigateToReview(draftId))
            }
        }
    }

    fun deleteDraft(draftWithUpload: DraftWithUpload) {
        val draft = draftWithUpload.draft
        viewModelScope.launch {
            cleanupManager.deleteDraft(draft.id)
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
                    publication = com.saurabh.artifact.model.SyncStatus.LocalOnly
                ),
                lifecycle = com.saurabh.artifact.model.ArtifactLifecycle.REVIEW_REQUIRED
            ))
        }
    }
}
