package com.saurabh.artifact.ui.player

import com.saurabh.artifact.model.Artifact

/**
 * Centralized mapping from internal Artifact model to Player-specific projection.
 */
fun Artifact.toPlayerArtifact(): PlayerArtifact {
    return PlayerArtifact(
        id = id,
        title = title,
        author = author,
        audioUrl = audioUrl,
        durationMs = durationMs,
        amplitudeData = amplitudeData,
        emotion = emotion,
        emotions = effectiveEmotions,
        createdAt = createdAt,
        transcript = transcript,
        recommendationState = recommendationState,
        isPublic = isPublic,
        isDraft = isDraft,
        episodeNumber = episodeNumber
    )
}
