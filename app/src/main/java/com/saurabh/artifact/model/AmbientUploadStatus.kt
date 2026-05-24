package com.saurabh.artifact.model

/**
 * Represents the rich, emotional states of an ambient upload session.
 * Uses atmospheric language to reduce network anxiety.
 */
sealed class AmbientUploadStatus {
    object Initializing : AmbientUploadStatus()
    object WaitingQuietly : AmbientUploadStatus()
    data class UploadingAudio(val progress: Float) : AmbientUploadStatus()
    object SavingArtifact : AmbientUploadStatus()
    object UpdatingFeed : AmbientUploadStatus()
    object Completed : AmbientUploadStatus()
    data class Error(val message: String, val recoverable: Boolean = true) : AmbientUploadStatus()

    fun getDisplayText(): String = when (this) {
        is Initializing -> "Creating a calm space..."
        is WaitingQuietly -> "Waiting quietly..."
        is UploadingAudio -> "Releasing your reflection..."
        is SavingArtifact -> "Securing your essence..."
        is UpdatingFeed -> "Sharing gently..."
        is Completed -> "Shared gently."
        is Error -> message
    }
}
