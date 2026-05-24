package com.saurabh.artifact.data.local

import androidx.room.*
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.SyncState
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

    @Query("SELECT * FROM artifact_drafts WHERE syncState = 'QUEUED' OR syncState = 'UPLOADING'")
    suspend fun getPendingUploads(): List<ArtifactDraftEntity>

    @Query("SELECT * FROM artifact_drafts WHERE syncState = 'RECORDING'")
    suspend fun getActiveRecordings(): List<ArtifactDraftEntity>

    @Query("UPDATE artifact_drafts SET draftState = :state, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateDraftState(id: String, state: ArtifactDraftState, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET syncState = :state, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateSyncState(id: String, state: SyncState, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET rawPcmPath = :path, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateRawPcmPath(id: String, path: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET interruptionReason = :reason, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateInterruptionReason(id: String, reason: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET durationMs = :durationMs, amplitudeData = :amplitudes, lastCheckpointTs = :checkpointTs, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateRecordingCheckpoint(id: String, durationMs: Long, amplitudes: List<Float>, checkpointTs: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET title = :title, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET uploadedBytes = :uploadedBytes, totalBytes = :totalBytes, uploadSessionUri = :sessionUri WHERE id = :draftId")
    suspend fun updateSyncProgress(draftId: String, uploadedBytes: Long, totalBytes: Long, sessionUri: String?)

    @Transaction
    suspend fun markAsPublished(id: String, remoteId: String) {
        val draft = getDraftById(id) ?: return
        update(draft.copy(
            draftState = ArtifactDraftState.PUBLISHED,
            remoteArtifactId = remoteId,
            updatedAt = System.currentTimeMillis()
        ))
    }

    @Query("SELECT * FROM artifact_drafts WHERE draftState = 'EMOTIONAL_CONFIRMATION'")
    suspend fun getDraftsAwaitingApproval(): List<ArtifactDraftEntity>

    @Query("UPDATE artifact_drafts SET isEmotionalReady = :isReady, publishConfidence = :confidence, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateEmotionalConfirmation(id: String, isReady: Boolean, confidence: Float, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET cooldownExpiry = :expiry, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateCooldown(id: String, expiry: Long?, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET draftState = 'APPROVED_FOR_PUBLISH', publishApprovalTimestamp = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun markAsApproved(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET frozenTranscriptJson = :transcriptJson, frozenAudioPath = :audioPath, frozenMetadataJson = :metadataJson, snapshotHash = :hash, updatedAt = :timestamp WHERE id = :id")
    suspend fun freezeSnapshot(id: String, transcriptJson: String, audioPath: String, metadataJson: String, hash: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET uploadStatus = :status, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateUploadStatus(id: String, status: com.saurabh.artifact.model.UploadStatus, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET maxReviewPositionMs = :positionMs, reviewCoverageBitmask = :coverage, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateReviewProgress(id: String, positionMs: Long, coverage: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE artifact_drafts SET lastPlaybackPositionMs = :positionMs, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateLastPlaybackPosition(id: String, positionMs: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM artifact_drafts WHERE remoteArtifactId = :artifactId")
    suspend fun getDraftByArtifactId(artifactId: String): ArtifactDraftEntity?

    @Query("DELETE FROM artifact_drafts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM artifact_drafts")
    suspend fun deleteAll()
}
