package com.saurabh.artifact.audio

import android.util.Log
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.repository.PublishApprovalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform-wide owner of the publishing lifecycle.
 * Ensures state coherence between validation, approval, and background upload.
 */
@Singleton
class PublishSessionManager @Inject constructor(
    private val approvalRepository: PublishApprovalRepository,
    private val publishingOrchestrator: PublishingOrchestrator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _activePublishingId = MutableStateFlow<String?>(null)
    val activePublishingId: StateFlow<String?> = _activePublishingId.asStateFlow()

    /**
     * Approves a draft for publication and begins the immutable snapshot process.
     * This moves the draft from UI-local state to platform-managed publishing state.
     */
    suspend fun approveAndPublish(
        draftId: String, 
        transcript: List<TranscriptSegment>
    ): Result<Unit> {
        _activePublishingId.value = draftId
        
        return try {
            val result = approvalRepository.approveAndFreeze(draftId, transcript)
            if (result.isSuccess) {
                Log.i("PublishSessionManager", "Draft $draftId approved and frozen. Handoff to Orchestrator.")
                publishingOrchestrator.approvePublishing(draftId)
                Result.success(Unit)
            } else {
                _activePublishingId.value = null
                result
            }
        } catch (e: Exception) {
            Log.e("PublishSessionManager", "Critical failure during approval handoff", e)
            _activePublishingId.value = null
            Result.failure(e)
        }
    }

    /**
     * Clears the active session marker. 
     * Usually called when the UI confirms the handoff was successful.
     */
    fun clearSession() {
        _activePublishingId.value = null
    }

    /**
     * Determines if a specific draft is currently in the active publishing pipeline.
     */
    fun isPublishing(draftId: String): Boolean {
        return _activePublishingId.value == draftId
    }
}
