package com.saurabh.artifact.model

/**
 * Represents the various states an artifact draft can be in during its lifecycle.
 * Note: This is a legacy flattened state model being gradually replaced by [DraftStatus].
 */
enum class ArtifactDraftState {
    SAVED_LOCALLY,
    READY_TO_REVIEW,
    REVIEWING,
    REVIEW_COMPLETED,
    REVIEWED,
    READY_TO_PUBLISH,
    WAITING_COOLDOWN,
    ERROR,
    SAVING,
    PUBLISHED,
    SAFETY_CHECK,
    TRANSCODING,
    PROCESSING,
    NORMALIZING,
    WAVEFORM_GENERATION,
    TRANSCRIBING,
    UPLOADING,
    AUDIO_UPLOADED,
    APPROVED_FOR_PUBLISH,
    WAITING_FOR_NETWORK,
    FAILED_UPLOAD,
    PRIVACY_SCANNING
}
