package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

/**
 * Lightweight recovery hint stored on the filesystem alongside audio files.
 * Encrypted using Keystore-backed AEAD to prevent tampering and exposure.
 */
@Serializable
data class DraftManifest(
    val draftId: String,
    val userId: String,
    val createdAt: Long,
    val mimeType: String,
    val title: String? = null,
    val emotion: Emotion? = null,
    val version: Int = 2
)
