package com.saurabh.artifact.data.local

import androidx.room.*
import com.saurabh.artifact.model.*
import com.saurabh.artifact.util.SecureString
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {
    /** User-scoped: Insert or replace a draft anchored to its creator. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(draft: ArtifactDraftEntity)

    /** User-scoped: Internal update for specific entity instances. */
    @Update
    suspend fun _updateInternal(draft: ArtifactDraftEntity)

    /** User-scoped: Transactional update with lifecycle validation and metadata immutability rules. */
    @Transaction
    suspend fun update(draft: ArtifactDraftEntity, isRecovery: Boolean = false) {
        val existing = getDraftById(draft.id, draft.userId)
        if (existing == null) {
            _updateInternal(draft)
            return
        }

        // Rule 1: After publication, reject metadata modifications
        if (existing.lifecycle == ArtifactLifecycle.PUBLISHED) {
            if (draft.title != existing.title || draft.emotions != existing.emotions || draft.emotion != existing.emotion) {
                android.util.Log.w("DraftDao", "Blocked metadata mutation on PUBLISHED draft ${draft.id}")
                return
            }
        }

        // Rule 2: During active publishing, reject metadata modifications
        val isActivelyPublishingInExisting = existing.status.publication is SyncStatus.Uploading ||
                                            existing.status.publication is SyncStatus.Finalizing ||
                                            existing.status.publication is SyncStatus.Queued
        val uploadTask = _getUploadTaskByDraftId(draft.id)
        val isActivelyPublishingInTask = uploadTask != null && (
            uploadTask.owner != null ||
            uploadTask.status is SyncStatus.Uploading ||
            uploadTask.status is SyncStatus.Finalizing ||
            uploadTask.status is SyncStatus.Queued
        )

        if (existing.lifecycle == ArtifactLifecycle.READY_TO_PUBLISH && (isActivelyPublishingInExisting || isActivelyPublishingInTask)) {
            if (draft.title != existing.title || draft.emotions != existing.emotions || draft.emotion != existing.emotion) {
                android.util.Log.w("DraftDao", "Blocked metadata mutation during active publishing on draft ${draft.id}")
                return
            }
        }

        // Rule 3: After approval but before publishing starts, if metadata changed, invalidate approval
        if (existing.lifecycle == ArtifactLifecycle.READY_TO_PUBLISH || existing.approvalToken != null) {
            val metadataChanged = draft.title != existing.title || draft.emotions != existing.emotions || draft.emotion != existing.emotion
            if (metadataChanged && draft.approvalToken != null) {
                android.util.Log.i("DraftDao", "Metadata mutation on approved draft ${draft.id}; invalidating approval")
                val invalidatedDraft = draft.copy(
                    approvalToken = null,
                    publishApprovalTimestamp = null,
                    frozenAudioPath = null,
                    frozenTranscriptJson = null,
                    approvalCompleted = false,
                    lifecycle = ArtifactLifecycle.METADATA_REQUIRED
                )
                _updateInternal(invalidatedDraft)
                return
            }
        }

        if (existing.lifecycle.canTransitionTo(draft.lifecycle, isRecovery)) {
            _updateInternal(draft)
        } else {
            android.util.Log.w("DraftDao", "Blocked backward lifecycle transition: ${existing.lifecycle} -> ${draft.lifecycle}")
        }
    }

    /** User-scoped: Delete a specific draft instance. */
    @Delete
    suspend fun delete(draft: ArtifactDraftEntity)

    /** User-scoped: Fetch a draft by ID and owner. */
    @Query("SELECT * FROM artifact_drafts WHERE id = :id AND userId = :userId")
    suspend fun getDraftById(id: String, userId: String): ArtifactDraftEntity?

    /** User-scoped: Observe a draft by ID and owner. */
    @Query("SELECT * FROM artifact_drafts WHERE id = :id AND userId = :userId")
    fun observeDraftById(id: String, userId: String): Flow<ArtifactDraftEntity?>

    /** User-scoped: Fetch a draft by its local path and owner. */
    @Query("SELECT * FROM artifact_drafts WHERE localAudioPath = :path AND userId = :userId")
    suspend fun getDraftByPath(path: String, userId: String): ArtifactDraftEntity?

    /** User-scoped: Fetch all drafts for a specific user. */
    @Query("SELECT * FROM artifact_drafts WHERE userId = :userId")
    suspend fun getAllDraftsByUserId(userId: String): List<ArtifactDraftEntity>

    /** System-maintenance: Fetch all drafts for storage reconciliation. */
    @Query("SELECT * FROM artifact_drafts")
    suspend fun getAllDrafts(): List<ArtifactDraftEntity>

    /** User-scoped: Observe all active drafts for a specific user. */
    @Query("SELECT * FROM artifact_drafts WHERE userId = :userId AND lifecycle NOT IN ('DELETED', 'DELETING') ORDER BY updatedAt DESC")
    fun observeDrafts(userId: String): Flow<List<ArtifactDraftEntity>>

    /** User-scoped: Fetch active recordings for a specific user. */
    @Query("SELECT * FROM artifact_drafts WHERE userId = :userId AND lifecycle = 'RECORDING'")
    suspend fun getActiveRecordings(userId: String): List<ArtifactDraftEntity>


    /** User-scoped: Update lifecycle with ownership enforcement. */
    @Transaction
    suspend fun updateLifecycle(id: String, userId: String, lifecycle: ArtifactLifecycle, timestamp: Long = System.currentTimeMillis(), isRecovery: Boolean = false) {
        val existing = getDraftById(id, userId)
        if (existing == null || existing.lifecycle.canTransitionTo(lifecycle, isRecovery)) {
            _updateStatusAndLifecycleInternal(id, userId, existing?.status ?: DraftStatus(), lifecycle, timestamp)
        } else {
            android.util.Log.w("DraftDao", "Blocked backward lifecycle transition: ${existing.lifecycle} -> $lifecycle")
        }
    }

    /** User-scoped: Internal update for status and lifecycle with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET status = :status, lifecycle = :lifecycle, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun _updateStatusAndLifecycleInternal(id: String, userId: String, status: DraftStatus, lifecycle: ArtifactLifecycle, timestamp: Long)

    /** User-scoped: Transactional status and lifecycle update with ownership enforcement. */
    @Transaction
    suspend fun updateStatusAndLifecycle(id: String, userId: String, status: DraftStatus, lifecycle: ArtifactLifecycle, timestamp: Long = System.currentTimeMillis(), isRecovery: Boolean = false) {
        val existing = getDraftById(id, userId)
        if (existing == null || existing.lifecycle.canTransitionTo(lifecycle, isRecovery)) {
            _updateStatusAndLifecycleInternal(id, userId, status, lifecycle, timestamp)
        } else {
            android.util.Log.w("DraftDao", "Blocked backward lifecycle transition: ${existing.lifecycle} -> $lifecycle")
        }
    }

    /** User-scoped: Update status with ownership enforcement. */
    @Transaction
    suspend fun updateStatus(id: String, userId: String, status: DraftStatus, timestamp: Long = System.currentTimeMillis()) {
        val existing = getDraftById(id, userId) ?: return
        _updateStatusAndLifecycleInternal(id, userId, status, existing.lifecycle, timestamp)
    }

    /** User-scoped: Update processing status with ownership enforcement. */
    @Transaction
    suspend fun updateProcessingStatus(id: String, userId: String, processing: ProcessingStatus, timestamp: Long = System.currentTimeMillis()) {
        val existing = getDraftById(id, userId) ?: return
        val newStatus = existing.status.copy(processing = processing)
        _updateStatusAndLifecycleInternal(id, userId, newStatus, existing.lifecycle, timestamp)
    }

    /** User-scoped: Update transcoding result with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET localAudioPath = :localAudioPath, mimeType = :mimeType, checksum = :checksum, isEncrypted = :isEncrypted, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun updateTranscodingResult(id: String, userId: String, localAudioPath: String, mimeType: String, checksum: String?, isEncrypted: Boolean, timestamp: Long = System.currentTimeMillis())

    /** User-scoped: Internal update for amplitude data with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET amplitudeData = :amplitudeData, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun _updateAmplitudeDataInternal(id: String, userId: String, amplitudeData: List<Float>, timestamp: Long)

    /** User-scoped: Update waveform result with ownership enforcement. */
    @Transaction
    suspend fun updateWaveformResult(id: String, userId: String, amplitudeData: List<Float>, timestamp: Long = System.currentTimeMillis()) {
        _updateAmplitudeDataInternal(id, userId, amplitudeData, timestamp)
        updateProcessingStatus(id, userId, ProcessingStatus.Idle, timestamp)
    }

    /** User-scoped: Finalize processing with ownership enforcement. */
    @Transaction
    suspend fun finalizeProcessing(id: String, userId: String, timestamp: Long = System.currentTimeMillis()) {
        val existing = getDraftById(id, userId) ?: return
        android.util.Log.d("FINALIZER_TRACE", "finalizeProcessing: existingLifecycle=${existing.lifecycle}")
        val targetLifecycle = if (existing.lifecycle == ArtifactLifecycle.PROCESSING) ArtifactLifecycle.REVIEW_REQUIRED else existing.lifecycle
        val newStatus = existing.status.copy(processing = ProcessingStatus.Idle)
        updateStatusAndLifecycle(id, userId, newStatus, targetLifecycle, timestamp)
    }


    /** User-scoped: Update raw PCM path with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET rawPcmPath = :path, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun updateRawPcmPath(id: String, userId: String, path: String, timestamp: Long = System.currentTimeMillis())

    /** User-scoped: Update interruption reason with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET interruptionReason = :reason, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun updateInterruptionReason(id: String, userId: String, reason: String, timestamp: Long = System.currentTimeMillis())

    /** User-scoped: Update recording checkpoint with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET durationMs = :durationMs, amplitudeData = :amplitudes, lastCheckpointTimestamp = :checkpointTimestamp, durableBytes = :durableBytes, updatedAt = :updatedAt WHERE id = :id AND userId = :userId")
    suspend fun updateRecordingCheckpoint(id: String, userId: String, durationMs: Long, amplitudes: List<Float>, checkpointTimestamp: Long, durableBytes: Long, updatedAt: Long = System.currentTimeMillis())

    /** User-scoped: Targeted update of draft prompt association with ownership and lifecycle enforcement. */
    @Query("UPDATE artifact_drafts SET promptId = :promptId, updatedAt = :timestamp WHERE id = :id AND userId = :userId AND lifecycle = :expectedLifecycle")
    suspend fun updateDraftPromptId(
        id: String,
        userId: String,
        promptId: String?,
        expectedLifecycle: ArtifactLifecycle = ArtifactLifecycle.RECORDING,
        timestamp: Long = System.currentTimeMillis()
    ): Int

    /** System/Internal: Query upload task status for draft to detect active publishing. */
    @Query("SELECT * FROM upload_tasks WHERE draftId = :draftId")
    suspend fun _getUploadTaskByDraftId(draftId: String): UploadTaskEntity?

    /** User-scoped: Internal raw metadata update before approval. */
    @Query("UPDATE artifact_drafts SET title = :title, emotion = :emotion, emotions = :emotions, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun _updateMetadataInternal(id: String, userId: String, title: String?, emotion: Emotion?, emotions: List<Emotion> = emptyList(), timestamp: Long = System.currentTimeMillis())

    /** User-scoped: Atomic metadata update and approval invalidation after approval. */
    @Query("UPDATE artifact_drafts SET title = :title, emotion = :emotion, emotions = :emotions, approvalToken = NULL, publishApprovalTimestamp = NULL, frozenAudioPath = NULL, frozenTranscriptJson = NULL, approvalCompleted = 0, lifecycle = :targetLifecycle, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun _updateMetadataAndInvalidateApprovalInternal(
        id: String,
        userId: String,
        title: String?,
        emotion: Emotion?,
        emotions: List<Emotion>,
        targetLifecycle: ArtifactLifecycle,
        timestamp: Long
    )

    /** User-scoped: Update title with ownership enforcement and lifecycle immutability rules. */
    @Transaction
    suspend fun updateTitle(id: String, userId: String, title: String?, timestamp: Long = System.currentTimeMillis()) {
        val existing = getDraftById(id, userId) ?: return
        updateMetadata(id, userId, title, existing.emotion, existing.emotions, timestamp)
    }

    /** User-scoped: Update metadata with ownership enforcement and lifecycle immutability rules. */
    @Transaction
    suspend fun updateMetadata(
        id: String,
        userId: String,
        title: String?,
        emotion: Emotion?,
        emotions: List<Emotion> = emptyList(),
        timestamp: Long = System.currentTimeMillis()
    ) {
        val draft = getDraftById(id, userId) ?: return

        // Rule 1: After publication: do not modify published metadata through draft editing operations
        if (draft.lifecycle == ArtifactLifecycle.PUBLISHED) {
            android.util.Log.w("DraftDao", "Blocked metadata mutation on PUBLISHED draft $id")
            return
        }

        // Rule 2: During active publishing: reject metadata mutation for that publication attempt
        val isActivelyPublishingInDraft = draft.status.publication is SyncStatus.Uploading || 
                                         draft.status.publication is SyncStatus.Finalizing ||
                                         draft.status.publication is SyncStatus.Queued
        val uploadTask = _getUploadTaskByDraftId(id)
        val isActivelyPublishingInTask = uploadTask != null && (
            uploadTask.owner != null || 
            uploadTask.status is SyncStatus.Uploading || 
            uploadTask.status is SyncStatus.Finalizing || 
            uploadTask.status is SyncStatus.Queued
        )

        if (draft.lifecycle == ArtifactLifecycle.READY_TO_PUBLISH && (isActivelyPublishingInDraft || isActivelyPublishingInTask)) {
            android.util.Log.w("DraftDao", "Blocked metadata mutation during active publishing on draft $id")
            return
        }

        // Rule 3: After approval but before publishing starts: metadata editing invalidates the previous approval and requires fresh approval
        if (draft.lifecycle == ArtifactLifecycle.READY_TO_PUBLISH || draft.approvalToken != null) {
            android.util.Log.i("DraftDao", "Metadata mutation on approved draft $id; atomically invalidating approval")
            _updateMetadataAndInvalidateApprovalInternal(
                id = id,
                userId = userId,
                title = title,
                emotion = emotion,
                emotions = emotions,
                targetLifecycle = ArtifactLifecycle.METADATA_REQUIRED,
                timestamp = timestamp
            )
            return
        }

        // Rule 4: Before approval: metadata can be edited normally
        _updateMetadataInternal(id, userId, title, emotion, emotions, timestamp)
    }

    /** User-scoped: Update upload checkpoint with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET uploadedAudioUrl = :url, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun updateUploadCheckpoint(id: String, userId: String, url: String, timestamp: Long = System.currentTimeMillis())

    /** User-scoped: Update sync progress with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET uploadedBytes = :uploadedBytes, totalBytes = :totalBytes, uploadSessionUri = :sessionUri WHERE id = :draftId AND userId = :userId")
    suspend fun updateSyncProgress(draftId: String, userId: String, uploadedBytes: Long, totalBytes: Long, sessionUri: String?)

    /** User-scoped: Invalidate upload session due to format change. */
    @Query("UPDATE artifact_drafts SET uploadFormatVersion = :version, uploadSessionUri = NULL, uploadedAudioUrl = NULL, uploadedBytes = 0, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun invalidateUploadSession(id: String, userId: String, version: Int, timestamp: Long = System.currentTimeMillis())

    /** User-scoped: Mark draft as published with ownership enforcement. */
    @Transaction
    suspend fun markAsPublished(id: String, userId: String, remoteId: String) {
        val draft = getDraftById(id, userId) ?: return
        update(
            draft.copy(
                status = draft.status.copy(
                    publication = SyncStatus.Synced,
                ),
                lifecycle = ArtifactLifecycle.PUBLISHED,
                remoteArtifactId = remoteId,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** User-scoped: Fetch drafts awaiting approval for a specific user. */
    @Query("SELECT * FROM artifact_drafts WHERE lifecycle = 'READY_TO_PUBLISH' AND userId = :userId")
    suspend fun getDraftsAwaitingApproval(userId: String): List<ArtifactDraftEntity>

    /** User-scoped: Update review progress with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET reviewProgress = :progress, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun updateReviewProgress(id: String, userId: String, progress: Float, timestamp: Long = System.currentTimeMillis())

    /** User-scoped: Internal update for approval state with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET status = :status, lifecycle = :lifecycle, publishApprovalTimestamp = :timestamp, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun _markAsApprovedInternal(id: String, userId: String, status: DraftStatus, lifecycle: ArtifactLifecycle, timestamp: Long)

    /** User-scoped: Transactional approval mark with ownership enforcement. */
    @Transaction
    suspend fun markAsApproved(id: String, userId: String, status: DraftStatus, lifecycle: ArtifactLifecycle, timestamp: Long = System.currentTimeMillis()) {
        val existing = getDraftById(id, userId)
        if (existing == null || existing.lifecycle.canTransitionTo(lifecycle)) {
            _markAsApprovedInternal(id, userId, status, lifecycle, timestamp)
        } else {
            android.util.Log.w("DraftDao", "Blocked backward lifecycle transition: ${existing.lifecycle} -> $lifecycle")
        }
    }

    /** User-scoped: Mark as approved (legacy/overload) with ownership enforcement. */
    @Transaction
    suspend fun markAsApproved(id: String, userId: String, status: DraftStatus, timestamp: Long = System.currentTimeMillis()) {
        val existing = getDraftById(id, userId) ?: return
        markAsApproved(id, userId, status, existing.lifecycle, timestamp)
    }

    /** User-scoped: Mark as approved (convenience) with ownership enforcement. */
    @Transaction
    suspend fun markAsApproved(id: String, userId: String) {
        val draft = getDraftById(id, userId) ?: return
        markAsApproved(id, userId, draft.status, ArtifactLifecycle.READY_TO_PUBLISH)
    }

    /** User-scoped: Mark as deleting with ownership enforcement and set localCleanupStatus atomically. */
    @Transaction
    suspend fun markAsDeleting(id: String, userId: String, timestamp: Long = System.currentTimeMillis()) {
        val draft = getDraftById(id, userId) ?: return
        if (draft.lifecycle.canTransitionTo(ArtifactLifecycle.DELETING)) {
            _markAsDeletingInternal(id, userId, draft.status, ArtifactLifecycle.DELETING, LocalCleanupStatus.PENDING, timestamp)
        } else {
            android.util.Log.w("DraftDao", "Blocked backward lifecycle transition: ${draft.lifecycle} -> DELETING")
        }
    }

    /** User-scoped: Internal update for deleting state with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET status = :status, lifecycle = :lifecycle, localCleanupStatus = :cleanupStatus, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun _markAsDeletingInternal(id: String, userId: String, status: DraftStatus, lifecycle: ArtifactLifecycle, cleanupStatus: LocalCleanupStatus, timestamp: Long)


    /** User-scoped: Freeze snapshot for publishing with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET frozenTranscriptJson = :transcriptJson, frozenAudioPath = :audioPath, approvalToken = :token, deviceFingerprint = :fingerprint, publishApprovalTimestamp = :timestamp, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun freezeSnapshot(id: String, userId: String, transcriptJson: SecureString?, audioPath: String, token: String, fingerprint: String, timestamp: Long = System.currentTimeMillis())

    /** User-scoped: Update sync status with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET status = :status, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun updateSyncStatus(id: String, userId: String, status: DraftStatus, timestamp: Long = System.currentTimeMillis())

    /** User-scoped: Update episode number with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET episodeNumber = :episodeNumber, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun updateEpisodeNumber(id: String, userId: String, episodeNumber: Long?, timestamp: Long = System.currentTimeMillis())


    /** System-maintenance: Fetch all draft IDs for system-level audits. */
    @Query("SELECT id FROM artifact_drafts")
    suspend fun getAllDraftIds(): List<String>

    /** User-scoped: Fetch draft by remote artifact ID and owner. */
    @Query("SELECT * FROM artifact_drafts WHERE remoteArtifactId = :artifactId AND userId = :userId")
    suspend fun getDraftByArtifactId(artifactId: String, userId: String): ArtifactDraftEntity?

    /** User-scoped: Fetch drafts by lifecycle and owner. */
    @Query("SELECT * FROM artifact_drafts WHERE lifecycle = :lifecycle AND userId = :userId")
    suspend fun getDraftsByLifecycle(lifecycle: ArtifactLifecycle, userId: String): List<ArtifactDraftEntity>

    /** System-maintenance: Fetch all drafts by lifecycle regardless of owner. */
    @Query("SELECT * FROM artifact_drafts WHERE lifecycle = :lifecycle")
    suspend fun getDraftsByLifecycleAgnostic(lifecycle: ArtifactLifecycle): List<ArtifactDraftEntity>


    /** User-scoped: Update studio state with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET titleCompleted = :title, emotionCompleted = :emotion, approvalCompleted = :approval, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun updateStudioState(
        id: String, 
        userId: String,
        title: Boolean, 
        emotion: Boolean, 
        approval: Boolean, 
        timestamp: Long = System.currentTimeMillis()
    )

    /** User-scoped: Internal mark review complete with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET reviewCompleted = 1, isListened = 1, lifecycle = :lifecycle, status = :status, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun __markReviewCompleteInternal(id: String, userId: String, status: DraftStatus, lifecycle: ArtifactLifecycle, timestamp: Long)

    /** User-scoped: Transactional mark review complete with ownership enforcement. */
    @Transaction
    suspend fun _markReviewCompleteInternal(id: String, userId: String, status: DraftStatus, lifecycle: ArtifactLifecycle, timestamp: Long = System.currentTimeMillis()) {
        val existing = getDraftById(id, userId)
        if (existing == null || existing.lifecycle.canTransitionTo(lifecycle)) {
            __markReviewCompleteInternal(id, userId, status, lifecycle, timestamp)
        } else {
            android.util.Log.w("DraftDao", "Blocked backward lifecycle transition: ${existing.lifecycle} -> $lifecycle")
        }
    }

    /** User-scoped: Partial mark review complete with ownership enforcement. */
    @Transaction
    suspend fun markReviewCompletePartial(id: String, userId: String) {
        val draft = getDraftById(id, userId) ?: return
        if (draft.reviewCompleted && draft.isListened && draft.lifecycle == ArtifactLifecycle.METADATA_REQUIRED) return
        _markReviewCompleteInternal(id, userId, draft.status, ArtifactLifecycle.METADATA_REQUIRED)
    }

    /** User-scoped: Dismiss draft with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET isDismissed = 1, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun dismissDraft(id: String, userId: String, timestamp: Long = System.currentTimeMillis())

    /** User-scoped: Update local cleanup status with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET localCleanupStatus = :status, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun updateLocalCleanupStatus(id: String, userId: String, status: LocalCleanupStatus, timestamp: Long = System.currentTimeMillis())

    /** User-scoped: Update local cleanup status by artifact ID and owner. */
    @Query("UPDATE artifact_drafts SET localCleanupStatus = :status, updatedAt = :timestamp WHERE remoteArtifactId = :artifactId AND userId = :userId")
    suspend fun updateLocalCleanupStatusByArtifactId(artifactId: String, userId: String, status: LocalCleanupStatus, timestamp: Long = System.currentTimeMillis())

    /** User-scoped: Update cleanup retry count with ownership enforcement. */
    @Query("UPDATE artifact_drafts SET cleanupRetryCount = :count, updatedAt = :timestamp WHERE id = :id AND userId = :userId")
    suspend fun updateCleanupRetryCount(id: String, userId: String, count: Int, timestamp: Long = System.currentTimeMillis())

    /** User-scoped: Update cleanup retry count by artifact ID and owner. */
    @Query("UPDATE artifact_drafts SET cleanupRetryCount = :count, updatedAt = :timestamp WHERE remoteArtifactId = :artifactId AND userId = :userId")
    suspend fun updateCleanupRetryCountByArtifactId(artifactId: String, userId: String, count: Int, timestamp: Long = System.currentTimeMillis())

    /** System-maintenance: Fetch unfinished cleanups for storage maintenance. */
    @Query("SELECT * FROM artifact_drafts WHERE localCleanupStatus IS NOT NULL AND localCleanupStatus != 'COMPLETED'")
    suspend fun getUnfinishedCleanups(): List<ArtifactDraftEntity>

    /** User-scoped: Delete a draft by ID and owner. */
    @Query("DELETE FROM artifact_drafts WHERE id = :id AND userId = :userId")
    suspend fun deleteById(id: String, userId: String)

    /** System-maintenance: Delete old published drafts for storage management. */
    @Query("DELETE FROM artifact_drafts WHERE lifecycle = 'PUBLISHED' AND updatedAt < :timestamp AND userId = :userId")
    suspend fun deleteOldPublishedDrafts(timestamp: Long, userId: String)

    /** User-scoped: Delete all drafts belonging to a specific user. */
    @Query("DELETE FROM artifact_drafts WHERE userId = :userId")
    suspend fun deleteAll(userId: String)

    /** System-maintenance: Internal lookup for background cleanup workers. */
    @Query("SELECT * FROM artifact_drafts WHERE id = :id")
    suspend fun internalGetDraftByIdAgnostic(id: String): ArtifactDraftEntity?

    /** System-maintenance: Internal lookup for background cleanup workers. */
    @Query("SELECT * FROM artifact_drafts WHERE remoteArtifactId = :artifactId")
    suspend fun internalGetDraftByArtifactIdAgnostic(artifactId: String): ArtifactDraftEntity?

    /** System-maintenance: Internal physical delete for background cleanup workers. */
    @Query("DELETE FROM artifact_drafts WHERE id = :id")
    suspend fun internalDeleteByIdAgnostic(id: String)
}
