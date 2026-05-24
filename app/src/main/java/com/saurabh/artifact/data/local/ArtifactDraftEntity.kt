package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.ReactionVisibilityMode
import com.saurabh.artifact.model.SyncState
import com.saurabh.artifact.model.UploadStatus

@Entity(tableName = "artifact_drafts")
data class ArtifactDraftEntity(
    @PrimaryKey
    val id: String,
    val localAudioPath: String,
    val rawPcmPath: String? = null, // Durable source for crash resilience
    val localTranscriptPath: String? = null,
    val waveformPath: String? = null,
    val title: String? = null,
    val description: String? = null,
    val emotion: String? = null,
    val isPublic: Boolean = true,
    val tags: List<String> = emptyList(),
    val durationMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    
    // Core State Machine
    val draftState: ArtifactDraftState = ArtifactDraftState.RECORDING,
    val uploadStatus: UploadStatus = UploadStatus.LOCAL_ONLY,
    val syncState: SyncState = SyncState.INITIALIZING,
    
    // Upload Progress
    val uploadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val uploadSessionUri: String? = null,
    val uploadAttemptCount: Int = 0,
    
    // Security & Privacy
    val isEncrypted: Boolean = false,
    val encryptionIv: String? = null,
    val checksum: String? = null,
    val approvalToken: String? = null,
    val deviceFingerprint: String? = null,
    
    // Emotional Pacing & Approval
    val cooldownExpiry: Long? = null,
    val publishApprovalTimestamp: Long? = null,
    val revocationTimestamp: Long? = null,
    val emotionalRiskScore: Float = 0f,
    val publishConfidence: Float = 0f,
    val isEmotionalReady: Boolean = false,
    val maxReviewPositionMs: Long = 0L,
    val lastPlaybackPositionMs: Long = 0L,
    val reviewCoverageBitmask: String? = null, // JSON/CSV string of heard segments
    val isReviewLocked: Boolean = true,
    val isListened: Boolean = false,
    
    // Metadata & Recovery
    val deviceId: String? = null,
    val transcriptionState: String = "IDLE",
    val remoteArtifactId: String? = null,
    val emotionalTone: String? = null,
    val safetyAnalysis: String? = null,
    val interruptionReason: String? = null,
    val lastCheckpointTs: Long = System.currentTimeMillis(),
    val isCorrupted: Boolean = false,
    val version: Int = 1,

    // Recording Session Info
    val mimeType: String = "audio/mpeg",
    val amplitudeData: List<Float> = emptyList(),
    val reactionVisibility: ReactionVisibilityMode? = null,

    // Immutable Snapshot for Publishing
    val frozenTranscriptJson: String? = null,
    val frozenAudioPath: String? = null,
    val frozenMetadataJson: String? = null,
    val snapshotHash: String? = null,

    // Final Ritual Data
    val transcriptSegmentsJson: String? = null,
    val sensitiveEntitiesJson: String? = null,
)
