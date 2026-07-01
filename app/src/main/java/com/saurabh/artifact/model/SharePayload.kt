package com.saurabh.artifact.model

/**
 * A privacy-safe payload for sharing artifacts.
 * Contains only public-facing metadata.
 */
data class SharePayload(
    val artifactId: String,
    val title: String,
    val authorName: String,
    val authorSigil: String = "",
    val shareUrl: String? = null // Placeholder for future deep links
)
