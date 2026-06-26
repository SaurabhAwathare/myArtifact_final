package com.saurabh.artifact.model

/**
 * Represents the local, anonymous identity of the user.
 * This is the primary identity marker used before/during anonymous sessions.
 */
data class UserProfile(
    val anonymousId: String,
    val username: String = "Quiet Presence",
    val sigil: String = "",
    val avatarSeed: String = "",
    val avatarColor: String = "#FFD700",
    val avatarConfig: AvatarConfig = AvatarConfig(),
    val isAnonymous: Boolean = true,
    val resonanceInCount: Long = 0,
    val resonanceOutCount: Long = 0
)
