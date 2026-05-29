package com.saurabh.artifact.audio

import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.AmbientUploadStatus
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.UploadSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates ambient upload state by observing the local database.
 * Provides a single source of truth for the UI's ambient upload bar.
 */
@Singleton
class UploadSessionManager @Inject constructor(
    private val draftDao: DraftDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _currentSession = MutableStateFlow<UploadSession?>(null)
    val currentSession: StateFlow<UploadSession?> = _currentSession.asStateFlow()

    // Track dismissed drafts by their state to prevent annoying re-pops on minor DB updates
    private val dismissedDraftStates = mutableMapOf<String, ArtifactDraftState>()

    init {
        observePendingUploads()
    }

    private fun observePendingUploads() {
        scope.launch {
            draftDao.observeDrafts()
                .map { drafts ->
                    // Find the most relevant active upload
                    drafts.firstOrNull { 
                        it.draftState == ArtifactDraftState.UPLOADING || 
                        it.draftState == ArtifactDraftState.AUDIO_UPLOADED ||
                        it.draftState == ArtifactDraftState.APPROVED_FOR_PUBLISH ||
                        it.draftState == ArtifactDraftState.ERROR // Keep error visible
                    }
                }
                .distinctUntilChangedBy { it?.id to it?.draftState to it?.uploadedBytes }
                .collect { activeDraft ->
                    if (activeDraft == null) {
                        // If we had a session that was completed or errored, we might want to keep it visible for a moment
                        // but for others we clear it immediately.
                        val currentStatus = _currentSession.value?.status
                        if (currentStatus !is AmbientUploadStatus.Completed && currentStatus !is AmbientUploadStatus.Error) {
                            _currentSession.value = null
                        }
                        return@collect
                    }

                    // Check if this specific draft state was already dismissed by the user
                    // We check BOTH ID and State to allow the same draft to re-appear if it moves to a new phase (e.g. ERROR -> UPLOADING)
                    if (dismissedDraftStates[activeDraft.id] == activeDraft.draftState) {
                        return@collect
                    }

                    val progress = if (activeDraft.totalBytes > 0) {
                        activeDraft.uploadedBytes.toFloat() / activeDraft.totalBytes.toFloat()
                    } else 0f

                    val status = when (activeDraft.draftState) {
                        ArtifactDraftState.APPROVED_FOR_PUBLISH -> AmbientUploadStatus.Initializing
                        ArtifactDraftState.UPLOADING -> {
                            if (progress < 0.95f) {
                                AmbientUploadStatus.UploadingAudio(progress)
                            } else {
                                AmbientUploadStatus.SavingArtifact
                            }
                        }
                        ArtifactDraftState.AUDIO_UPLOADED -> AmbientUploadStatus.SavingArtifact
                        ArtifactDraftState.PUBLISHED -> AmbientUploadStatus.Completed
                        ArtifactDraftState.WAITING_FOR_NETWORK -> AmbientUploadStatus.WaitingQuietly
                        ArtifactDraftState.FAILED_UPLOAD -> AmbientUploadStatus.Error(
                            "Holding safely. We'll try sharing it again later.",
                            recoverable = true
                        )
                        ArtifactDraftState.ERROR -> AmbientUploadStatus.Error(
                            "Your reflection is safe. Something went wrong with the sync.",
                            recoverable = true
                        )
                        else -> AmbientUploadStatus.Initializing
                    }

                    _currentSession.value = UploadSession(
                        draftId = activeDraft.id,
                        title = activeDraft.title ?: "Untitled Artifact",
                        status = status,
                        progress = progress
                    )

                    // Auto-dismiss completed or error session after a delay
                    if (status is AmbientUploadStatus.Completed || status is AmbientUploadStatus.Error) {
                        scope.launch {
                            // Shorter delay for errors to clear UI faster on startup if it was a stale error
                            val delayMs = if (status is AmbientUploadStatus.Error) 5000L else 5000L
                            kotlinx.coroutines.delay(delayMs)
                            if (_currentSession.value?.draftId == activeDraft.id && _currentSession.value?.status == status) {
                                _currentSession.value = null
                            }
                        }
                    }
                }
        }
    }

    fun dismissSession() {
        val current = _currentSession.value
        if (current != null) {
            // Record this dismissal to prevent it from coming back until state changes
            scope.launch {
                val draft = draftDao.getDraftById(current.draftId)
                if (draft != null) {
                    dismissedDraftStates[draft.id] = draft.draftState
                }
                _currentSession.value = null
            }
        } else {
            _currentSession.value = null
        }
    }
}
