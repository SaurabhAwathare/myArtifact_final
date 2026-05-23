package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

/**
 * Production-grade identity model for procedural abstract avatars.
 * Aligns with the platform's emotional philosophy of reflection and anonymity.
 */
@Serializable
data class AnonymousIdentity(
    val anonymousName: String,
    val avatarSeed: String,
    val avatarPalette: List<Long>? = null,
    val avatarStyle: String = "AURIC",
    val version: Int = 1
)
