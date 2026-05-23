package com.saurabh.artifact.model

import androidx.compose.runtime.Immutable

/**
 * Immutable UI model for an Artifact to ensure efficient recomposition in LazyLists.
 * Combines basic artifact data with detail states and user-specific status.
 */
@Immutable
data class ArtifactUiModel(
    val artifact: Artifact,
    val isUnlocked: Boolean = false,
    val detail: ArtifactDetail? = null,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0,
    val isCurrentlyFocused: Boolean = false,
    val isHydrated: Boolean = false,
    val isPlaceholder: Boolean = false
) {
    val id: String get() = artifact.id
    val duration: Long get() = artifact.duration
    val comments: List<ArtifactComment> get() = detail?.comments ?: emptyList()
}
