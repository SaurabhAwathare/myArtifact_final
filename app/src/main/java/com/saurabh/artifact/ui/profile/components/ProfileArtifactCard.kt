package com.saurabh.artifact.ui.profile.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactReactionCounts
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.ui.components.AmbientWaveform
import com.saurabh.artifact.ui.components.ResonanceDisplay
import com.saurabh.artifact.ui.components.WaveformContext
import com.saurabh.artifact.ui.player.components.ModerationBanner
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing
import java.text.SimpleDateFormat

/**
 * A specialized card for the Profile screen that emphasizes "intentional emotional ownership".
 * Provides a voice-first listening control, warm waveform, and bottom-left emotion context.
 */
@Composable
fun ProfileArtifactCard(
    artifact: Artifact,
    isDraft: Boolean,
    isOwner: Boolean,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onPublishClick: () -> Unit = {},
    isSaved: Boolean = false,
    onUnsave: () -> Unit = {},
    isBuffering: Boolean = false,
    progress: Float = 0f,
    isListened: Boolean = false,
    reviewProgress: Float = 0f,
    onResonatorsCountClick: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(value = false) }
    var showRenameDialog by remember { mutableStateOf(value = false) }
    var showDeleteDialog by remember { mutableStateOf(value = false) }

    val configuration = LocalConfiguration.current
    val locale = ConfigurationCompat.getLocales(configuration)[0] ?: configuration.locales[0]
    val dateFormat = remember(locale) { SimpleDateFormat("MMM d, yyyy", locale) }
    val displayDate = remember(artifact.createdAt, dateFormat) { dateFormat.format(artifact.createdAt.toDate()) }

    val displayEmotionWithEmoji = remember(artifact.emotion) {
        if (artifact.emotion.isBlank()) return@remember ""
        val emotionEnum = Emotion.entries.find { 
            it.label.equals(artifact.emotion, ignoreCase = true) || 
            it.name.equals(artifact.emotion, ignoreCase = true) 
        }
        if (emotionEnum != null) {
            "${emotionEnum.emoji} ${emotionEnum.label.lowercase()}"
        } else {
            artifact.emotion.lowercase()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Small),
        shape = MaterialTheme.shapes.large,
        color = ArtifactTheme.colors.surfaceHearth.copy(alpha = 0.5f),
        onClick = onPlayClick
    ) {
        Column(modifier = Modifier.padding(Spacing.Large)) {
            if (isOwner) {
                ModerationBanner(
                    recommendationState = artifact.recommendationState,
                    modifier = Modifier.padding(bottom = Spacing.Large)
                )
            }

            // Top: Title, Date and Management Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = artifact.title.ifEmpty { "Untitled Moment" },
                        style = ArtifactTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = ArtifactTheme.colors.onSurfaceMain,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = displayDate,
                        style = ArtifactTheme.typography.labelSmall,
                        color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.6f)
                    )
                }

                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Management Options",
                        tint = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.4f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.Large))

            // Center: Audio Controls & Waveform
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 44.dp Circular Play/Pause/Buffering Control
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(ArtifactTheme.colors.waveformActive.copy(alpha = 0.08f))
                        .clickable(onClick = onPlayClick),
                    contentAlignment = Alignment.Center
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = ArtifactTheme.colors.waveformActive
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = ArtifactTheme.colors.waveformActive,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(Spacing.Medium))

                // Ambient Waveform
                AmbientWaveform(
                    amplitudes = artifact.amplitudeData.takeIf { it.isNotEmpty() } ?: listOf(0.3f, 0.5f, 0.4f, 0.6f, 0.2f, 0.7f, 0.5f, 0.4f),
                    progress = progress,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    isPaused = !isPlaying,
                    context = if (isPlaying) WaveformContext.Player else WaveformContext.Feed,
                    id = artifact.id
                )

                Spacer(modifier = Modifier.width(Spacing.Medium))

                // Duration Text
                Text(
                    text = formatDuration(artifact.durationMs),
                    style = ArtifactTheme.typography.labelSmall,
                    color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(Spacing.Medium))

            // Bottom Row: Emotion Tag (Bottom-Left) & Contextual Metadata (Bottom-Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bottom-Left: Emotion Tag Chip
                if (displayEmotionWithEmoji.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(ArtifactTheme.colors.waveformActive.copy(alpha = 0.05f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = displayEmotionWithEmoji,
                            style = ArtifactTheme.typography.labelSmall,
                            color = ArtifactTheme.colors.waveformActive.copy(alpha = 0.8f)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(0.dp))
                }

                // Bottom-Right: Contextual Information (Draft Status or Reaction Counts)
                if (isDraft) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isListened) ArtifactTheme.colors.waveformActive else Color.White.copy(alpha = 0.3f))
                        )
                        Spacer(modifier = Modifier.width(Spacing.Small))
                        Text(
                            text = if (isListened) "Ready to publish" else "Review to publish (${(reviewProgress * 100).toInt()}%)",
                            style = ArtifactTheme.typography.labelMedium,
                            color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.5f)
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Large)
                    ) {
                        if (artifact.reactionCount > 0) {
                            ResonanceDisplay(
                                counts = ArtifactReactionCounts(
                                    artifactId = artifact.id,
                                    totalCount = artifact.reactionCount,
                                    visibility = artifact.reactionVisibility
                                ),
                                isOwner = isOwner,
                                onClick = onResonatorsCountClick
                            )
                        }
                    }
                }
            }
        }
    }

    if (showMenu) {
        ArtifactManagementBottomSheet(
            isOwner = isOwner,
            isDraft = isDraft,
            isSaved = isSaved,
            reviewProgress = reviewProgress,
            isListened = isListened,
            onRenameClick = { showRenameDialog = true },
            onPublishClick = onPublishClick,
            onReviewClick = onPlayClick,
            onDeleteClick = { showDeleteDialog = true },
            onUnsaveClick = onUnsave
        ) {
            showMenu = false
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            initialTitle = artifact.title,
            onConfirm = { 
                onRename(it)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )
    }

    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            isPublished = !isDraft,
            onConfirm = {
                onDelete()
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false }
        )
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%d:%02d".format(mins, secs)
}
