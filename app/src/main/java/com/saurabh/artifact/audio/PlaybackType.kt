package com.saurabh.artifact.audio

enum class PlaybackType {
    ARTIFACT,
    DRAFT_PREVIEW
}

data class ActivePlayback(
    val artifactId: String?,
    val playbackType: PlaybackType
)
