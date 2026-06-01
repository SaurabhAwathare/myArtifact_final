package com.saurabh.artifact.audio

enum class PlaybackType {
    ARTIFACT,
    DRAFT_PREVIEW,
    PROFILE_PREVIEW
}

data class ActivePlayback(
    val artifactId: String?,
    val playbackType: PlaybackType
)
