package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.saurabh.artifact.model.*
import com.saurabh.artifact.util.SecureString
import kotlinx.serialization.Serializable

@Serializable
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
    val emotion: Emotion? = null,
    val isPublic: Boolean = true,
    val isListened: Boolean = false,
    val tags: List<String> = emptyList(),
    val durationMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    
    // Composite State Model
    val status: DraftStatus = DraftStatus(),
    val lifecycle: ArtifactLifecycle = ArtifactLifecycle.RECORDING,
    
    // Upload Progress
    override val uploadedBytes: Long = 0,
    override val totalBytes: Long = 0,
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
    val maxReviewPositionMs: Long = 0,
    val reviewProgress: Float = 0f,
    
    // Metadata & Recovery
    val deviceId: String? = null,
    val transcriptionState: String = "IDLE",
    val remoteArtifactId: String? = null,
    val emotionalTone: EmotionalTone? = null,
    val primaryStyle: ConversationStyle? = null,
    val safetyAnalysis: String? = null,
    val interruptionReason: String? = null,
    val lastCheckpointTimestamp: Long = System.currentTimeMillis(),
    val durableBytes: Long = 0, // Option A: Track bytes successfully synced to disk
    val isCorrupted: Boolean = false,
    val version: Int = 1,

    // Recording Session Info
    val mimeType: String = "audio/wav",
    val amplitudeData: List<Float> = emptyList(),
    val reactionVisibility: ReactionVisibilityMode? = null,

    // Upload Checkpoints
    val uploadedAudioUrl: String? = null,

    // Immutable Snapshot for Publishing
    val frozenTranscriptJson: SecureString? = null,
    val frozenAudioPath: String? = null,
    val frozenMetadataJson: SecureString? = null,
    val snapshotHash: String? = null,

    // Final Ritual Data
    val transcriptSegmentsJson: SecureString? = null,
    val sensitiveEntitiesJson: SecureString? = null,

    // Publishing Studio Completion Flags
    val reviewCompleted: Boolean = false,
    val titleCompleted: Boolean = false,
    val emotionCompleted: Boolean = false,
    val approvalCompleted: Boolean = false,

    // Redesign: Persistent Dismissal & Activity Tracking
    val isDismissed: Boolean = false,
) : UploadProgress
