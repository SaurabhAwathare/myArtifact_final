package com.saurabh.artifact.audio

import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.repository.DraftRepository
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the unified publishing state by observing the local database,
 * active WorkManager jobs, and foreground service status.
 */
@Singleton
class PublishStateManager @Inject constructor(
    private val draftRepository: DraftRepository,
    private val draftDao: DraftDao,
    private val publishingOrchestrator: PublishingOrchestrator,
    private val workManager: WorkManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _currentPublishState = MutableStateFlow<PublishState?>(null)
    val currentPublishState: StateFlow<PublishState?> = _currentPublishState.asStateFlow()

    init {
        observeActivity()
    }

    private fun observeActivity() {
        scope.launch {
            combine(
                draftRepository.observeActivePublishingSessionWithUpload(),
                UploadService.isServiceRunning,
                UploadService.activeDraftId
            ) { active, isServiceRunning, activeServiceDraftId ->
                Triple(active, isServiceRunning, activeServiceDraftId)
            }.collect { (active, isServiceRunning, activeServiceDraftId) ->
                if (active == null) {
                    _currentPublishState.value = null
                    return@collect
                }

                val draft = active.draft
                val task = active.uploadTask
                
                // Persistent Dismissal Check
                if (draft.isDismissed) {
                    _currentPublishState.value = null
                    return@collect
                }

                val id = draft.id
                val title = draft.title ?: "Untitled Artifact"
                val syncStatus = task?.status ?: draft.status.publication
                val progress = (task ?: draft).progress

                // 1. Verify Actual Activity
                val isServiceActive = isServiceRunning && activeServiceDraftId == id
                val workInfos = try {
                    workManager.getWorkInfosByTag("process_$id").get() + 
                    workManager.getWorkInfosByTag("publish_$id").get()
                } catch (e: Exception) {
                    emptyList<WorkInfo>()
                }
                
                val isWorkActive = workInfos.any { 
                    it.state == WorkInfo.State.ENQUEUED || 
                    it.state == WorkInfo.State.RUNNING || 
                    it.state == WorkInfo.State.BLOCKED 
                }

                // If it's an error, we show it regardless of active workers (to allow retry)
                if (syncStatus is SyncStatus.Failed) {
                    _currentPublishState.value = PublishState.Error(draftId = id, title = title, message = syncStatus.error)
                    return@collect
                }

                // Activity Check: If no service or worker is active, don't show the bar (Ghost Prevention)
                if (!isServiceActive && !isWorkActive && draft.lifecycle != ArtifactLifecycle.PUBLISHED) {
                    _currentPublishState.value = null
                    return@collect
                }

                // 2. Map to Granular State
                val newState = when {
                    draft.lifecycle == ArtifactLifecycle.PUBLISHED -> 
                        PublishState.Published(draftId = id, title = title, artifactId = draft.remoteArtifactId ?: "")
                    
                    syncStatus is SyncStatus.WaitingForNetwork -> 
                        PublishState.Uploading(draftId = id, title = title, progress = progress, isWaitingForNetwork = true)
                    
                    syncStatus is SyncStatus.Uploading || syncStatus is SyncStatus.Finalizing -> {
                        if (progress < 0.95f && syncStatus !is SyncStatus.Finalizing) {
                            PublishState.Uploading(draftId = id, title = title, progress = progress)
                        } else {
                            PublishState.Finalizing(draftId = id, title = title)
                        }
                    }
                    
                    draft.lifecycle == ArtifactLifecycle.PROCESSING -> {
                        val processing = draft.status.processing
                        val displayTitle = if (processing is ProcessingStatus.Active) {
                            when (processing.stage) {
                                ProcessingStage.SAVING -> "Securing reflection..."
                                ProcessingStage.TRANSCODING -> "Preparing audio..."
                                ProcessingStage.NORMALIZING -> "Optimizing clarity..."
                                ProcessingStage.WAVEFORM_GENERATION -> "Generating waveform..."
                                ProcessingStage.TRANSCRIBING -> "Transcribing audio..."
                                ProcessingStage.PRIVACY_SCANNING -> "Running privacy checks..."
                                ProcessingStage.SAFETY_CHECK -> "Finalizing safety..."
                                ProcessingStage.ENCRYPTING_BACKUP -> "Securing backup..."
                            }
                        } else "Creating a calm space..."
                        
                        PublishState.Preparing(draftId = id, title = title, displayStatus = displayTitle)
                    }

                    draft.lifecycle == ArtifactLifecycle.READY_TO_PUBLISH ->
                        PublishState.Preparing(draftId = id, title = title, displayStatus = "Enqueuing publication...")
                    
                    else -> null
                }

                _currentPublishState.value = newState
            }
        }
        
        // Watchdog: Periodically clean up stale processing drafts
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(10 * 60 * 1000) // Every 10 minutes
                performWatchdogCleanup()
            }
        }
    }

    private suspend fun performWatchdogCleanup() {
        val staleThreshold = System.currentTimeMillis() - 24 * 60 * 60 * 1000 // 24 hours
        val drafts = draftDao.getAllDrafts().filter { 
            it.lifecycle == ArtifactLifecycle.PROCESSING && it.updatedAt < staleThreshold 
        }
        
        drafts.forEach { draft ->
            Log.w("PublishStateManager", "Watchdog: Marking stale draft ${draft.id} as FAILED")
            draftRepository.updateStatus(draft.id) { 
                it.copy(processing = ProcessingStatus.Failed) 
            }
        }
    }

    fun dismissSession() {
        val current = _currentPublishState.value
        if (current != null) {
            scope.launch {
                draftDao.dismissDraft(current.draftId)
                _currentPublishState.value = null
            }
        }
    }

    fun retryPublish(draftId: String) {
        scope.launch {
            publishingOrchestrator.retryPublishing(draftId)
        }
    }
}
