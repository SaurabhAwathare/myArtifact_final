package com.saurabh.artifact.data.local

import androidx.room.*
import com.saurabh.artifact.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(draft: ArtifactDraftEntity)

    @Update
    suspend fun update(draft: ArtifactDraftEntity)

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

    @Query("SELECT * FROM artifact_drafts ORDER BY updatedAt DESC")
    fun observeDrafts(): Flow<List<ArtifactDraftEntity>>

    @Query("SELECT * FROM artifact_drafts WHERE status LIKE '%\"publication\":{\"type\":\"Uploading\"%' OR status LIKE '%\"publication\":\"Queued\"%'")
    suspend fun getPendingUploadsLegacy(): List<ArtifactDraftEntity>

    @Query("SELECT * FROM artifact_drafts WHERE lifecycle = 'RECORDING'")
    suspend fun getActiveRecordings(): List<ArtifactDraftEntity>

    @Query("UPDATE artifact_drafts SET status = :status, lifecycle = :lifecycle, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateStatusAndLifecycle(id: String, status: DraftStatus, lifecycle: ArtifactLifecycle, timestamp: Long = System.currentTimeMillis())

    @Transaction
    suspend fun updateStatus(id: String, status: DraftStatus, timestamp: Long = System.currentTimeMillis()) {
        updateStatusAndLifecycle(id, status, status.lifecycle, timestamp)
    }


    @Query("UPDATE artifact_drafts SET rawPcmPath = :path, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateRawPcmPath(id: String, path: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET interruptionReason = :reason, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateInterruptionReason(id: String, reason: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET durationMs = :durationMs, amplitudeData = :amplitudes, lastCheckpointTs = :checkpointTs, durableBytes = :durableBytes, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateRecordingCheckpoint(id: String, durationMs: Long, amplitudes: List<Float>, checkpointTs: Long, durableBytes: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET title = :title, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateTitle(id: String, title: String?, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET title = :title, emotion = :emotion, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateMetadata(id: String, title: String?, emotion: String?, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET uploadedAudioUrl = :url, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateUploadCheckpoint(id: String, url: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET uploadedBytes = :uploadedBytes, totalBytes = :totalBytes, uploadSessionUri = :sessionUri WHERE id = :draftId")
    suspend fun updateSyncProgress(draftId: String, uploadedBytes: Long, totalBytes: Long, sessionUri: String?)

    @Transaction
    suspend fun markAsPublished(id: String, remoteId: String) {
        val draft = getDraftById(id) ?: return
        update(draft.copy(
            status = draft.status.copy(
                lifecycle = ArtifactLifecycle.PUBLISHED,
                publication = SyncStatus.Synced
            ),
            lifecycle = ArtifactLifecycle.PUBLISHED,
            remoteArtifactId = remoteId,
            updatedAt = System.currentTimeMillis()
        ))
    }

    @Query("SELECT * FROM artifact_drafts WHERE lifecycle = 'READY_TO_PUBLISH'")
    suspend fun getDraftsAwaitingApproval(): List<ArtifactDraftEntity>

    @Query("UPDATE artifact_drafts SET isEmotionalReady = :isReady, publishConfidence = :confidence, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateEmotionalConfirmation(id: String, isReady: Boolean, confidence: Float, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET cooldownExpiry = :expiry, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateCooldown(id: String, expiry: Long?, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET status = :status, lifecycle = :lifecycle, publishApprovalTimestamp = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun markAsApproved(id: String, status: DraftStatus, lifecycle: ArtifactLifecycle, timestamp: Long = System.currentTimeMillis())

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

    @Query("UPDATE artifact_drafts SET frozenTranscriptJson = :transcriptJson, frozenAudioPath = :audioPath, frozenMetadataJson = :metadataJson, snapshotHash = :hash, updatedAt = :timestamp WHERE id = :id")
    suspend fun freezeSnapshot(id: String, transcriptJson: String, audioPath: String, metadataJson: String, hash: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET status = :status, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: DraftStatus, timestamp: Long = System.currentTimeMillis())


    @Query("SELECT id FROM artifact_drafts")
    suspend fun getAllDraftIds(): List<String>

    @Query("SELECT * FROM artifact_drafts WHERE remoteArtifactId = :artifactId")
    suspend fun getDraftByArtifactId(artifactId: String): ArtifactDraftEntity?

    @Query("SELECT * FROM artifact_drafts WHERE lifecycle = :lifecycle")
    suspend fun getDraftsByLifecycle(lifecycle: ArtifactLifecycle): List<ArtifactDraftEntity>


    @Query("DELETE FROM artifact_drafts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM artifact_drafts")
    suspend fun deleteAll()
}
