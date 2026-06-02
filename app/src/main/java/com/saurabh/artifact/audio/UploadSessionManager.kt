package com.saurabh.artifact.audio

import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.repository.DraftRepository
import com.saurabh.artifact.repository.DraftWithUpload
import com.saurabh.artifact.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the unified publishing state by observing the local database and active upload tasks.
 * Provides a single source of truth for the UI's ambient upload bar.
 */
@Singleton
class PublishStateManager @Inject constructor(
    private val draftRepository: DraftRepository,
    private val draftDao: DraftDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _currentPublishState = MutableStateFlow<PublishState?>(null)
    val currentPublishState: StateFlow<PublishState?> = _currentPublishState.asStateFlow()

    // Track dismissed drafts by their lifecycle to prevent annoying re-pops
    private val dismissedDraftLifecycles = mutableMapOf<String, ArtifactLifecycle>()

    init {
        observePendingUploads()
    }

    private fun observePendingUploads() {
        scope.launch {
            draftRepository.observeDraftsWithUploads()
                .map { draftsWithUploads ->
                    // Find the most relevant active upload/publish
                    draftsWithUploads.firstOrNull { item ->
                        val lifecycle = item.draft.status.lifecycle
                        val sync = item.uploadTask?.status ?: item.draft.status.publication
                        
                        lifecycle == ArtifactLifecycle.READY_TO_PUBLISH ||
                        lifecycle == ArtifactLifecycle.PUBLISHED ||
                        sync is SyncStatus.Uploading ||
                        sync is SyncStatus.WaitingForNetwork ||
                        sync is SyncStatus.Failed ||
                        sync is SyncStatus.Finalizing
                    }
                }
                .distinctUntilChangedBy { 
                    it?.draft?.id to it?.draft?.status?.lifecycle to it?.uploadTask?.status to it?.uploadTask?.uploadedBytes 
                }
                .collect { active ->
                    if (active == null) {
                        val currentState = _currentPublishState.value
                        if (currentState !is PublishState.Published && currentState !is PublishState.Error) {
                            _currentPublishState.value = null
                        }
                        return@collect
                    }

                    val draft = active.draft
                    val task = active.uploadTask
                    
                    // Check dismissal
                    if (dismissedDraftLifecycles[draft.id] == draft.status.lifecycle) {
                        return@collect
                    }

                    val title = draft.title ?: "Untitled Artifact"
                    val id = draft.id
                    
                    val syncStatus = task?.status ?: draft.status.publication
                    val uploaded = task?.uploadedBytes ?: draft.uploadedBytes
                    val total = task?.totalBytes ?: draft.totalBytes

                    val progress = if (total > 0) uploaded.toFloat() / total.toFloat() else 0f

                    val newState = when {
                        draft.status.lifecycle == ArtifactLifecycle.PUBLISHED -> 
                            PublishState.Published(id, title, draft.remoteArtifactId ?: id)
                        
                        syncStatus is SyncStatus.Failed -> 
                            PublishState.Error(id, title, syncStatus.error, syncStatus.recoverable)
                        
                        syncStatus is SyncStatus.WaitingForNetwork -> 
                            PublishState.Uploading(id, title, progress, isWaitingForNetwork = true)
                        
                        syncStatus is SyncStatus.Uploading -> {
                            if (progress < 0.95f) {
                                PublishState.Uploading(id, title, progress)
                            } else {
                                PublishState.Finalizing(id, title)
                            }
                        }
                        
                        syncStatus is SyncStatus.Finalizing -> PublishState.Finalizing(id, title)
                        
                        draft.status.lifecycle == ArtifactLifecycle.READY_TO_PUBLISH -> 
                            PublishState.Preparing(id, title)
                        
                        else -> null
                    }

                    if (newState != null) {
                        _currentPublishState.value = newState

                        // Auto-dismiss terminal states
                        if (newState is PublishState.Published || newState is PublishState.Error) {
                            scope.launch {
                                delay(5000L)
                                if (_currentPublishState.value?.draftId == draft.id) {
                                    _currentPublishState.value = null
                                }
                            }
                        }
                    } else {
                        _currentPublishState.value = null
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
                    dismissedDraftLifecycles[draft.id] = draft.status.lifecycle
                }
                _currentPublishState.value = null
            }
        }
    }
}
