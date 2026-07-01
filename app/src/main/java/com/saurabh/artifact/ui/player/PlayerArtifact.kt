package com.saurabh.artifact.ui.player

import androidx.compose.runtime.Immutable
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.TranscriptSegment
import com.google.firebase.Timestamp

/**
 * A projection model specifically for the Player component.
 * Contains only the fields required for playback and UI display.
 * Sensitive fields like userId, reporterIds, and moderation flags are excluded.
 */
@Immutable
data class PlayerArtifact(
    val id: String,
    val title: String,
    val author: AuthorSnapshot,
    val audioUrl: String,
    val durationMs: Long,
    val amplitudeData: List<Float>,
    val emotion: String,
    val createdAt: Timestamp,
    val transcript: List<TranscriptSegment>,
    val isDraft: Boolean
)
