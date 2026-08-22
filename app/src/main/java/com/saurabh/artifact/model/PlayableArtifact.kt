package com.saurabh.artifact.model

import com.saurabh.artifact.data.local.ArtifactDraftEntity

/**
 * A unified model that represents any artifact that can be played by the Global Player.
 * This can be a local draft (newly recorded or existing) or a published artifact from Firestore.
 */
data class PlayableArtifact(
    val id: String,
    val title: String,
    val audioUrl: String,
    val authorName: String,
    val authorSigil: String,
    val sigilSeed: String,
    val durationMs: Long,
    val sourceType: PlaybackSource,
    val emotion: String = "",
    val originalArtifact: Artifact? = null,
    val originalDraft: ArtifactDraftEntity? = null
)

enum class PlaybackSource {
    REVIEW_DRAFT,
    FEED_PLAYBACK,
    PROFILE_PLAYBACK,
    NOTIFICATION,
    DEEP_LINK
}
