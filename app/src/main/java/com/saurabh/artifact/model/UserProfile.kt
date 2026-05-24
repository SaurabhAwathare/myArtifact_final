package com.saurabh.artifact.model

/**
 * Represents the local, anonymous identity of the user.
 * This is the primary identity marker used before/during anonymous sessions.
 */
data class UserProfile(
    val anonymousId: String,
    @Deprecated("Use avatarConfig")
    val identityEmoji: String = "✨",
    val username: String = "Quiet Presence",
    val sigil: String = "",
    val avatarSeed: String = "",
    val avatarConfig: AvatarConfig = AvatarConfig(),
)
