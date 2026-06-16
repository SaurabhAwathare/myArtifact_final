package com.saurabh.artifact.data.local

import androidx.room.*
import com.saurabh.artifact.model.*
import com.saurabh.artifact.util.SecureString
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(draft: ArtifactDraftEntity)

    @Update
    suspend fun _updateInternal(draft: ArtifactDraftEntity)

    @Transaction
    suspend fun update(draft: ArtifactDraftEntity, isRecovery: Boolean = false) {
        val existing = getDraftById(draft.id)
        if (existing == null || existing.lifecycle.canTransitionTo(draft.lifecycle, isRecovery)) {
            _updateInternal(draft)
        } else {
            android.util.Log.w("DraftDao", "Blocked backward lifecycle transition for ${draft.id}: ${existing.lifecycle} -> ${draft.lifecycle}")
        }
    }

    @Delete
    suspend fun delete(draft: ArtifactDraftEntity)

    @Query("SELECT * FROM artifact_drafts WHERE id = :id")
    suspend fun getDraftById(id: String): ArtifactDraftEntity?

    @Query("SELECT * FROM artifact_drafts WHERE id = :id")
    fun observeDraftById(id: String): Flow<ArtifactDraftEntity?>

    @Query("SELECT * FROM artifact_drafts WHERE localAudioPath = :path")
    suspend fun getDraftByPath(path: String): ArtifactDraftEntity?

    @Query("SELECT * FROM artifact_drafts")
    suspend fun getAllDrafts(): List<ArtifactDraftEntity>

    @Query("SELECT * FROM artifact_drafts WHERE lifecycle NOT IN ('DELETED', 'DELETING') ORDER BY updatedAt DESC")
    fun observeDrafts(): Flow<List<ArtifactDraftEntity>>

    @Query("SELECT * FROM artifact_drafts WHERE status LIKE '%\"publication\":{\"type\":\"Uploading\"%' OR status LIKE '%\"publication\":\"Queued\"%'")
    suspend fun getPendingUploadsLegacy(): List<ArtifactDraftEntity>

    @Query("SELECT * FROM artifact_drafts WHERE lifecycle = 'RECORDING'")
    suspend fun getActiveRecordings(): List<ArtifactDraftEntity>

    @Query("UPDATE artifact_drafts SET lifecycle = :lifecycle, updatedAt = :timestamp WHERE id = :id")
    suspend fun _updateLifecycleInternal(id: String, lifecycle: ArtifactLifecycle, timestamp: Long)

    @Transaction
    suspend fun updateLifecycle(id: String, lifecycle: ArtifactLifecycle, timestamp: Long = System.currentTimeMillis(), isRecovery: Boolean = false) {
        val existing = getDraftById(id)
        if (existing == null || existing.lifecycle.canTransitionTo(lifecycle, isRecovery)) {
            _updateLifecycleInternal(id, lifecycle, timestamp)
        } else {
            android.util.Log.w("DraftDao", "Blocked backward lifecycle transition for $id: ${existing.lifecycle} -> $lifecycle")
        }
    }

    @Query("UPDATE artifact_drafts SET status = :status, lifecycle = :lifecycle, updatedAt = :timestamp WHERE id = :id")
    suspend fun _updateStatusAndLifecycleInternal(id: String, status: DraftStatus, lifecycle: ArtifactLifecycle, timestamp: Long)

    @Transaction
    suspend fun updateStatusAndLifecycle(id: String, status: DraftStatus, lifecycle: ArtifactLifecycle, timestamp: Long = System.currentTimeMillis(), isRecovery: Boolean = false) {
        val existing = getDraftById(id)
        if (existing == null || existing.lifecycle.canTransitionTo(lifecycle, isRecovery)) {
            _updateStatusAndLifecycleInternal(id, status, lifecycle, timestamp)
        } else {
            android.util.Log.w("DraftDao", "Blocked backward lifecycle transition for $id: ${existing.lifecycle} -> $lifecycle")
        }
    }

    @Transaction
    suspend fun updateStatus(id: String, status: DraftStatus, timestamp: Long = System.currentTimeMillis()) {
        updateStatusAndLifecycle(id, status, status.lifecycle, timestamp)
    }

    @Transaction
    suspend fun updateProcessingStatus(id: String, processing: ProcessingStatus, timestamp: Long = System.currentTimeMillis()) {
        val existing = getDraftById(id) ?: return
        val newStatus = existing.status.copy(processing = processing)
        _updateStatusAndLifecycleInternal(id, newStatus, existing.lifecycle, timestamp)
    }

    @Query("UPDATE artifact_drafts SET localAudioPath = :localAudioPath, checksum = :checksum, isEncrypted = :isEncrypted, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateTranscodingResult(id: String, localAudioPath: String, checksum: String?, isEncrypted: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET localTranscriptPath = :localTranscriptPath, emotionalTone = :emotionalTone, primaryStyle = :primaryStyle, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateTranscriptionResult(id: String, localTranscriptPath: String, emotionalTone: EmotionalTone?, primaryStyle: ConversationStyle?, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET amplitudeData = :amplitudeData, updatedAt = :timestamp WHERE id = :id")
    suspend fun _updateAmplitudeDataInternal(id: String, amplitudeData: List<Float>, timestamp: Long)

    @Transaction
    suspend fun updateWaveformResult(id: String, amplitudeData: List<Float>, timestamp: Long = System.currentTimeMillis()) {
        _updateAmplitudeDataInternal(id, amplitudeData, timestamp)
        updateProcessingStatus(id, ProcessingStatus.Idle, timestamp)
    }

    @Query("UPDATE artifact_drafts SET sensitiveEntitiesJson = :sensitiveEntitiesJson, updatedAt = :timestamp WHERE id = :id")
    suspend fun updatePrivacyResult(id: String, sensitiveEntitiesJson: SecureString?, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET safetyAnalysis = :safetyAnalysis, emotionalRiskScore = :emotionalRiskScore, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateSafetyResult(id: String, safetyAnalysis: String?, emotionalRiskScore: Float, timestamp: Long = System.currentTimeMillis())

    @Transaction
    suspend fun finalizeProcessing(id: String, timestamp: Long = System.currentTimeMillis()) {
        val existing = getDraftById(id) ?: return
        val newStatus = existing.status.copy(
            lifecycle = ArtifactLifecycle.REVIEW_REQUIRED,
            processing = ProcessingStatus.Completed
        )
        updateStatusAndLifecycle(id, newStatus, ArtifactLifecycle.REVIEW_REQUIRED, timestamp)
    }


    @Query("UPDATE artifact_drafts SET rawPcmPath = :path, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateRawPcmPath(id: String, path: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET interruptionReason = :reason, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateInterruptionReason(id: String, reason: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET durationMs = :durationMs, amplitudeData = :amplitudes, lastCheckpointTimestamp = :checkpointTimestamp, durableBytes = :durableBytes, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateRecordingCheckpoint(id: String, durationMs: Long, amplitudes: List<Float>, checkpointTimestamp: Long, durableBytes: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET title = :title, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateTitle(id: String, title: String?, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET title = :title, emotion = :emotion, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateMetadata(id: String, title: String?, emotion: Emotion?, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET uploadedAudioUrl = :url, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateUploadCheckpoint(id: String, url: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET uploadedBytes = :uploadedBytes, totalBytes = :totalBytes, uploadSessionUri = :sessionUri WHERE id = :draftId")
    suspend fun updateSyncProgress(draftId: String, uploadedBytes: Long, totalBytes: Long, sessionUri: String?)

    @Transaction
    suspend fun markAsPublished(id: String, remoteId: String) {
        val draft = getDraftById(id) ?: return
        update(
            draft.copy(
                status = draft.status.copy(
                    lifecycle = ArtifactLifecycle.PUBLISHED,
                    publication = SyncStatus.Synced,
                ),
                lifecycle = ArtifactLifecycle.PUBLISHED,
                remoteArtifactId = remoteId,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    @Query("SELECT * FROM artifact_drafts WHERE lifecycle = 'READY_TO_PUBLISH'")
    suspend fun getDraftsAwaitingApproval(): List<ArtifactDraftEntity>

    @Query("UPDATE artifact_drafts SET isEmotionalReady = :isReady, publishConfidence = :confidence, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateEmotionalConfirmation(id: String, isReady: Boolean, confidence: Float, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET reviewProgress = :progress, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateReviewProgress(id: String, progress: Float, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET cooldownExpiry = :expiry, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateCooldown(id: String, expiry: Long?, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET status = :status, lifecycle = :lifecycle, publishApprovalTimestamp = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun _markAsApprovedInternal(id: String, status: DraftStatus, lifecycle: ArtifactLifecycle, timestamp: Long)

    @Transaction
    suspend fun markAsApproved(id: String, status: DraftStatus, lifecycle: ArtifactLifecycle, timestamp: Long = System.currentTimeMillis()) {
        val existing = getDraftById(id)
        if (existing == null || existing.lifecycle.canTransitionTo(lifecycle)) {
            _markAsApprovedInternal(id, status, lifecycle, timestamp)
        } else {
            android.util.Log.w("DraftDao", "Blocked backward lifecycle transition for $id: ${existing.lifecycle} -> $lifecycle")
        }
    }

    @Transaction
    suspend fun markAsApproved(id: String, status: DraftStatus, timestamp: Long = System.currentTimeMillis()) {
        markAsApproved(id, status, status.lifecycle, timestamp)
    }

    @Transaction
    suspend fun markAsApproved(id: String) {
        val draft = getDraftById(id) ?: return
        val newStatus = draft.status.copy(lifecycle = ArtifactLifecycle.READY_TO_PUBLISH)
        markAsApproved(id, newStatus)
    }

    @Transaction
    suspend fun markAsDeleting(id: String) {
        val draft = getDraftById(id) ?: return
        if (draft.lifecycle != ArtifactLifecycle.DELETING) {
            val newStatus = draft.status.copy(lifecycle = ArtifactLifecycle.DELETING)
            updateStatusAndLifecycle(id, newStatus, ArtifactLifecycle.DELETING)
        }
    }

    @Query("UPDATE artifact_drafts SET frozenTranscriptJson = :transcriptJson, frozenAudioPath = :audioPath, frozenMetadataJson = :metadataJson, snapshotHash = :hash, approvalToken = :token, deviceFingerprint = :fingerprint, updatedAt = :timestamp WHERE id = :id")
    suspend fun freezeSnapshot(id: String, transcriptJson: String, audioPath: String, metadataJson: String, hash: String, token: String, fingerprint: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET status = :status, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: DraftStatus, timestamp: Long = System.currentTimeMillis())


    @Query("SELECT id FROM artifact_drafts")
    suspend fun getAllDraftIds(): List<String>

    @Query("SELECT * FROM artifact_drafts WHERE remoteArtifactId = :artifactId")
    suspend fun getDraftByArtifactId(artifactId: String): ArtifactDraftEntity?

    @Query("SELECT * FROM artifact_drafts WHERE lifecycle = :lifecycle")
    suspend fun getDraftsByLifecycle(lifecycle: ArtifactLifecycle): List<ArtifactDraftEntity>


    @Query("UPDATE artifact_drafts SET reviewCompleted = :review, titleCompleted = :title, emotionCompleted = :emotion, approvalCompleted = :approval, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateStudioState(
        id: String, 
        review: Boolean, 
        title: Boolean, 
        emotion: Boolean, 
        approval: Boolean, 
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE artifact_drafts SET reviewCompleted = 1, isListened = 1, lifecycle = :lifecycle, status = :status, updatedAt = :timestamp WHERE id = :id")
    suspend fun __markReviewCompleteInternal(id: String, status: DraftStatus, lifecycle: ArtifactLifecycle, timestamp: Long)

    @Transaction
    suspend fun _markReviewCompleteInternal(id: String, status: DraftStatus, lifecycle: ArtifactLifecycle, timestamp: Long = System.currentTimeMillis()) {
        val existing = getDraftById(id)
        if (existing == null || existing.lifecycle.canTransitionTo(lifecycle)) {
            __markReviewCompleteInternal(id, status, lifecycle, timestamp)
        } else {
            android.util.Log.w("DraftDao", "Blocked backward lifecycle transition for $id: ${existing.lifecycle} -> $lifecycle")
        }
    }

    @Transaction
    suspend fun markReviewCompletePartial(id: String) {
        val draft = getDraftById(id) ?: return
        // Avoid redundant updates if already in correct state
        if (draft.reviewCompleted && draft.isListened && draft.lifecycle == ArtifactLifecycle.METADATA_REQUIRED) return
        
        val newStatus = draft.status.copy(lifecycle = ArtifactLifecycle.METADATA_REQUIRED)
        _markReviewCompleteInternal(id, newStatus, ArtifactLifecycle.METADATA_REQUIRED)
    }

    @Query("UPDATE artifact_drafts SET isDismissed = 1, updatedAt = :timestamp WHERE id = :id")
    suspend fun dismissDraft(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM artifact_drafts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM artifact_drafts WHERE lifecycle = 'PUBLISHED' AND updatedAt < :timestamp")
    suspend fun deleteOldPublishedDrafts(timestamp: Long)

    @Query("DELETE FROM artifact_drafts")
    suspend fun deleteAll()
}
