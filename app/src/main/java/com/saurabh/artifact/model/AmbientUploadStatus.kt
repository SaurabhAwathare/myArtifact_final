package com.saurabh.artifact.model

/**
 * Represents the rich, emotional states of an ambient upload session.
 */
sealed class AmbientUploadStatus {
    object Initializing : AmbientUploadStatus()
    data class UploadingAudio(val progress: Float) : AmbientUploadStatus()
    object SavingArtifact : AmbientUploadStatus()
    object UpdatingFeed : AmbientUploadStatus()
    object Completed : AmbientUploadStatus()
    data class Error(val message: String, val recoverable: Boolean = true) : AmbientUploadStatus()

    fun getDisplayText(): String = when (this) {
        is Initializing -> "Preparing your artifact..."
        is UploadingAudio -> "A thought entering the archive..."
        is SavingArtifact -> "Safeguarding your voice..."
        is UpdatingFeed -> "Sharing with the world..."
        is Completed -> "Your artifact is now part of the archive."
        is Error -> message
    }
}
