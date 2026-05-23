package com.saurabh.artifact.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.FeedArtifact
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
    currentPosition: Long = 0,
    onDeleteClick: () -> Unit = {}
) {
    val currentUser = ArtifactTheme.currentUser

    Column(modifier = modifier) {
        // Recommendation Context Label
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = Spacing.Large, vertical = Spacing.Small)
                .alpha(0.6f)
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = ArtifactTheme.colors.waveformActive
            )
            Spacer(modifier = Modifier.width(Spacing.Small))
            Text(
                text = feedArtifact.reason.label,
                style = MaterialTheme.typography.labelMedium,
                color = ArtifactTheme.colors.onSurfaceMuted
            )
        }

        ArtifactCard(
            artifact = feedArtifact.artifact,
            isPlaying = isPlaying,
            onPlayClick = onPlayClick,
            currentPosition = if (feedArtifact.isUnfinished && !isPlaying) feedArtifact.lastPositionMs else currentPosition,
            duration = feedArtifact.artifact.duration * 1000L,
            onDeleteClick = onDeleteClick,
            currentUserId = currentUser?.id
        )
    }
}
