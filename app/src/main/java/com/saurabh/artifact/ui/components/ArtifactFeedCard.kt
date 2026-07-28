package com.saurabh.artifact.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.saurabh.artifact.model.FeedArtifact
import com.saurabh.artifact.ui.feed.HydrationLevel
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing

/**
 * A specialized version of ArtifactCard for the For You feed.
 * Includes recommendation context and emotional alignment metadata.
 */
@Composable
fun ArtifactFeedCard(
    feedArtifact: FeedArtifact,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    hydrationLevel: HydrationLevel = HydrationLevel.FULL,
    currentPosition: Long = 0,
    onReportClick: () -> Unit = {},
    onFeedbackClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onAuthorClick: (String) -> Unit = {},
    currentUserId: String? = null,
    artifactDetail: com.saurabh.artifact.model.ArtifactDetail? = null
) {
    ArtifactCard(
        artifact = feedArtifact.artifact,
        isPlaying = isPlaying,
        onPlayClick = onPlayClick,
        isBuffering = isBuffering,
        hydrationLevel = hydrationLevel,
        currentPosition = currentPosition,
        durationMs = feedArtifact.artifact.durationMs,
        onReportClick = onReportClick,
        onFeedbackClick = onFeedbackClick,
        onSettingsClick = onSettingsClick,
        onAuthorClick = onAuthorClick,
        currentUserId = currentUserId,
        artifactDetail = artifactDetail,
        recommendationReason = feedArtifact.reason,
        modifier = modifier
    )
}
