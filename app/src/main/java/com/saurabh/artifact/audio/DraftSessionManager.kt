package com.saurabh.artifact.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.SyncState
import com.saurabh.artifact.repository.RecordingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DraftSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val draftDao: DraftDao,
    private val recordingRepository: RecordingRepository,
    private val localDraftManager: LocalDraftManager
) {
    private val _activeDraft = MutableStateFlow<ArtifactDraftEntity?>(null)
    val activeDraft: StateFlow<ArtifactDraftEntity?> = _activeDraft.asStateFlow()

    suspend fun startNewSession(isComment: Boolean = false) {
        val extension = if (isComment) "m4a" else "m4a"
        val file = localDraftManager.createDraftFile(extension)
        val draftId = recordingRepository.createDraft(file.absolutePath, 0)
        val draft = draftDao.getDraftById(draftId)
        
        _activeDraft.value = draft
        
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra("draft_id", draftId)
            putExtra(RecordingService.EXTRA_IS_COMMENT, isComment)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopSession() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)
        // _activeDraft.value = null // REMOVED: Service will clear this when finalization is done
    }

    fun pauseSession() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    fun resumeSession() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_RESUME
        }
        context.startService(intent)
    }

    fun cancelSession() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_CANCEL
        }
        context.startService(intent)
        _activeDraft.value = null
    }
}
