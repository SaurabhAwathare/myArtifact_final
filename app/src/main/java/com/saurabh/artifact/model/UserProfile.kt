package com.saurabh.artifact.model

/**
 * Represents the local, anonymous identity of the user.
 * This is the primary identity marker used before/during anonymous sessions.
 */
data class UserProfile(
    val anonymousId: String,
    val identityEmoji: String = "✨",
    val username: String = "Anonymous Soul",
    val avatarConfig: AvatarConfig? = null,
)
