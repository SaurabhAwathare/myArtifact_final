package com.saurabh.artifact.model

import com.google.firebase.Timestamp

/**
 * Represents a private emotional bookmark of an artifact.
 * This is stored under users/{uid}/savedArtifacts/{artifactId}
 * and is completely invisible to the artifact author.
 */
data class SavedArtifact(
    val artifactId: String = "",
    val savedAt: Timestamp = Timestamp.now(),
    // Metadata for offline/local-first behavior
    val title: String = "",
    val authorName: String = "",
    val audioUrl: String = "",
    val emotionTag: String = "",
    // Private archival context
    val shelf: String = "Stayed With Me",
    val personalNote: String? = null
)
