package com.saurabh.artifact.audio

import com.saurabh.artifact.data.local.DraftDao
import kotlinx.coroutines.delay
import com.saurabh.artifact.model.PublishState
import com.saurabh.artifact.model.ArtifactDraftState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the unified publishing state by observing the local database.
 * Provides a single source of truth for the UI's ambient upload bar.
 */
@Singleton
class PublishStateManager @Inject constructor(
    private val draftDao: DraftDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _currentPublishState = MutableStateFlow<PublishState?>(null)
    val currentPublishState: StateFlow<PublishState?> = _currentPublishState.asStateFlow()

    // Track dismissed drafts by their state to prevent annoying re-pops on minor DB updates
    private val dismissedDraftStates = mutableMapOf<String, ArtifactDraftState>()

    init {
        observePendingUploads()
    }

    private fun observePendingUploads() {
        scope.launch {
            draftDao.observeDrafts()
                .map { drafts ->
                    // Find the most relevant active upload/publish
                    drafts.firstOrNull { 
                        it.draftState == ArtifactDraftState.UPLOADING || 
                        it.draftState == ArtifactDraftState.AUDIO_UPLOADED ||
                        it.draftState == ArtifactDraftState.APPROVED_FOR_PUBLISH ||
                        it.draftState == ArtifactDraftState.ERROR || // Keep error visible
                        it.draftState == ArtifactDraftState.PUBLISHED // Handle transition to published
                    }
                }
                .distinctUntilChangedBy { it?.id to it?.draftState to it?.uploadedBytes }
                .collect { activeDraft ->
                    if (activeDraft == null) {
                        val currentState = _currentPublishState.value
                        if (currentState !is PublishState.Published && currentState !is PublishState.Error) {
                            _currentPublishState.value = null
                        }
                        return@collect
                    }

                    // Check dismissal
                    if (dismissedDraftStates[activeDraft.id] == activeDraft.draftState) {
                        return@collect
                    }

                    val progress = if (activeDraft.totalBytes > 0) {
                        activeDraft.uploadedBytes.toFloat() / activeDraft.totalBytes.toFloat()
                    } else 0f

                    val title = activeDraft.title ?: "Untitled Artifact"
                    val id = activeDraft.id

                    val newState = when (activeDraft.draftState) {
                        ArtifactDraftState.APPROVED_FOR_PUBLISH -> PublishState.Preparing(id, title)
                        ArtifactDraftState.UPLOADING -> {
                            if (progress < 0.95f) {
                                PublishState.Uploading(id, title, progress)
                            } else {
                                PublishState.Finalizing(id, title)
                            }
                        }
                        ArtifactDraftState.AUDIO_UPLOADED -> PublishState.Finalizing(id, title)
                        ArtifactDraftState.PUBLISHED -> PublishState.Published(id, title, activeDraft.remoteArtifactId ?: id)
                        ArtifactDraftState.WAITING_FOR_NETWORK -> PublishState.Uploading(id, title, progress, isWaitingForNetwork = true)
                        ArtifactDraftState.FAILED_UPLOAD, ArtifactDraftState.ERROR -> PublishState.Error(
                            id, title, "Something went wrong with the sync.", isRecoverable = true
                        )
                        else -> PublishState.Idle(id, title)
                    }

                    _currentPublishState.value = newState

                    // Auto-dismiss terminal states
                    if (newState is PublishState.Published || newState is PublishState.Error) {
                        scope.launch {
                            delay(5000L)
                            if (_currentPublishState.value?.draftId == activeDraft.id) {
                                _currentPublishState.value = null
                            }
                        }
                    }
                }
        }
    }

    fun dismissSession() {
        val current = _currentPublishState.value
        if (current != null) {
            scope.launch {
                val draft = draftDao.getDraftById(current.draftId)
                if (draft != null) {
                    dismissedDraftStates[draft.id] = draft.draftState
                }
                _currentPublishState.value = null
            }
        }
    }
}

