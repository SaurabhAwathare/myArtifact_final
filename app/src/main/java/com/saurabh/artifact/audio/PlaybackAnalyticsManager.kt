package com.saurabh.artifact.audio

import android.os.Bundle
import android.util.Log
// import com.google.firebase.analytics.FirebaseAnalytics
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.PlayableArtifact
import javax.inject.Inject
import javax.inject.Singleton

interface PlaybackAnalytics {
    fun trackPlaybackStart(artifact: Artifact)
    fun trackPlayableStart(playable: PlayableArtifact)
    fun trackPlaybackPause(artifact: Artifact, positionMs: Long)
    fun trackPlaybackComplete(artifact: Artifact)
    fun trackPlaybackError(artifact: Artifact?, error: String)
}

@Singleton
class PlaybackAnalyticsManager @Inject constructor(
    private val diagnosticLogger: com.saurabh.artifact.diagnostics.DiagnosticLogger
) : PlaybackAnalytics {

    override fun trackPlaybackStart(artifact: Artifact) {
        diagnosticLogger.info(
            com.saurabh.artifact.diagnostics.DiagnosticCategory.PLAYER, 
            "PLAYBACK_STARTED", 
            mapOf(com.saurabh.artifact.diagnostics.LogKeys.ARTIFACT_ID to artifact.id)
        )
    }

    override fun trackPlayableStart(playable: PlayableArtifact) {
        diagnosticLogger.info(
            com.saurabh.artifact.diagnostics.DiagnosticCategory.PLAYER, 
            "PLAYABLE_STARTED", 
            mapOf(
                com.saurabh.artifact.diagnostics.LogKeys.ARTIFACT_ID to playable.id,
                "source" to playable.sourceType.name,
                "is_draft" to (playable.originalDraft != null)
            )
        )
    }

    override fun trackPlaybackPause(artifact: Artifact, positionMs: Long) {
        diagnosticLogger.info(
            com.saurabh.artifact.diagnostics.DiagnosticCategory.PLAYER, 
            "PLAYBACK_PAUSED", 
            mapOf(
                com.saurabh.artifact.diagnostics.LogKeys.ARTIFACT_ID to artifact.id,
                "pos" to positionMs
            )
        )
    }

    override fun trackPlaybackComplete(artifact: Artifact) {
        diagnosticLogger.info(
            com.saurabh.artifact.diagnostics.DiagnosticCategory.PLAYER, 
            "PLAYBACK_COMPLETED", 
            mapOf(com.saurabh.artifact.diagnostics.LogKeys.ARTIFACT_ID to artifact.id)
        )
    }

    override fun trackPlaybackError(artifact: Artifact?, error: String) {
        diagnosticLogger.error(
            com.saurabh.artifact.diagnostics.DiagnosticCategory.PLAYER, 
            "PLAYBACK_FAILED", 
            mapOf(
                com.saurabh.artifact.diagnostics.LogKeys.ARTIFACT_ID to (artifact?.id ?: "unknown"),
                "message" to error
            )
        )
    }
}
