package com.saurabh.artifact.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A lightweight, denormalized snapshot of a user's public identity
 * at the time an artifact was created.
 */
@Immutable
@Serializable
data class AuthorSnapshot(
    val anonymousId: String = "",
    val name: String = "",
    val sigil: String = "", // Added for soft uniqueness
    val avatarSeed: String = "",
    val avatarColor: String = "#FFD700",
    val avatarConfig: AvatarConfig = AvatarConfig()
)
