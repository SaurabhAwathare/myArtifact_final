package com.saurabh.artifact.audio

enum class PlaybackType {
    ARTIFACT,
    DRAFT_PREVIEW
}

data class ActivePlayback(
    val artifactId: String?,
    val playbackType: PlaybackType,
    val source: com.saurabh.artifact.model.PlaybackSource = com.saurabh.artifact.model.PlaybackSource.FEED_PLAYBACK
)
