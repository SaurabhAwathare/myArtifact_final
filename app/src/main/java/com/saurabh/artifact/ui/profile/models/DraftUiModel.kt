package com.saurabh.artifact.ui.profile.models

import com.saurabh.artifact.model.Artifact

/**
 * UI representation of a draft on the Profile screen.
 * Combines the domain [Artifact] with draft-specific UI state.
 */
data class DraftUiModel(
    val artifact: Artifact,
    val reviewProgress: Float,
    val isListened: Boolean
)
