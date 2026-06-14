package com.saurabh.artifact.audio

import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
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
    private val firebaseAnalytics: FirebaseAnalytics,
) : PlaybackAnalytics {

    override fun trackPlaybackStart(artifact: Artifact) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.ITEM_ID, artifact.id)
            putString(FirebaseAnalytics.Param.ITEM_NAME, artifact.title)
            putString("emotion", artifact.emotion)
            putLong("duration_ms", artifact.durationMs)
            putString("source", "feed") // Default for legacy
        }
        firebaseAnalytics.logEvent("playback_start", bundle)
        Log.d("PlaybackAnalytics", "Tracked start: ${artifact.id}")
    }

    override fun trackPlayableStart(playable: PlayableArtifact) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.ITEM_ID, playable.id)
            putString(FirebaseAnalytics.Param.ITEM_NAME, playable.title)
            putString("emotion", playable.emotion)
            putLong("duration_ms", playable.durationMs)
            putString("source", playable.sourceType.name)
            putBoolean("is_draft", playable.originalDraft != null)
        }
        firebaseAnalytics.logEvent("playback_start", bundle)
        Log.d("PlaybackAnalytics", "Tracked playable start: ${playable.id} from ${playable.sourceType}")
    }

    override fun trackPlaybackPause(artifact: Artifact, positionMs: Long) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.ITEM_ID, artifact.id)
            putLong("position_ms", positionMs)
        }
        firebaseAnalytics.logEvent("playback_pause", bundle)
        Log.d("PlaybackAnalytics", "Tracked pause: ${artifact.id} at $positionMs")
    }

    override fun trackPlaybackComplete(artifact: Artifact) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.ITEM_ID, artifact.id)
        }
        firebaseAnalytics.logEvent("playback_complete", bundle)
        Log.d("PlaybackAnalytics", "Tracked complete: ${artifact.id}")
    }

    override fun trackPlaybackError(artifact: Artifact?, error: String) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.ITEM_ID, artifact?.id ?: "unknown")
            putString("error_message", error)
        }
        firebaseAnalytics.logEvent("playback_error", bundle)
        Log.e("PlaybackAnalytics", "Tracked error: $error for ${artifact?.id}")
    }
}
