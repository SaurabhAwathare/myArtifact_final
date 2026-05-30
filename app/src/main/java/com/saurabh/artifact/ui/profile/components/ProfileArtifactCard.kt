package com.saurabh.artifact.ui.profile.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.ui.components.AmbientWaveform
import com.saurabh.artifact.ui.components.WaveformContext
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.*

/**
 * A specialized card for the Profile screen that emphasizes "intentional emotional ownership".
 * It feels calmer and more grounded than the feed cards.
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
    onViewComments: () -> Unit,
    isSaved: Boolean = false,
    onUnsave: () -> Unit = {},
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    progress: Float = 0f
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val displayDate = remember(artifact.createdAt) { dateFormat.format(artifact.createdAt.toDate()) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Small),
        shape = MaterialTheme.shapes.large,
        color = ArtifactTheme.colors.surfaceHearth.copy(alpha = 0.5f),
        onClick = onPlayClick
    ) {
        Column(modifier = Modifier.padding(Spacing.Large)) {
            // ... Top row
            // Top: Title, Date and Menu
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
                        maxLines = 1,
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

            // Center: Waveform Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                AmbientWaveform(
                    amplitudes = artifact.amplitudeData.takeIf { it.isNotEmpty() } ?: listOf(0.3f, 0.5f, 0.4f, 0.6f, 0.2f, 0.7f, 0.5f, 0.4f),
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    isPaused = !isPlaying,
                    context = WaveformContext.Mini
                )

                // Overlay Play/Pause indicator subtly if playing
                if (isPlaying || isBuffering) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(ArtifactTheme.colors.waveformActive.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = ArtifactTheme.colors.waveformActive
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Pause,
                                contentDescription = null,
                                tint = ArtifactTheme.colors.waveformActive,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.Medium))

            // Bottom: Contextual Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isDraft) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.3f))
                        )
                        Spacer(modifier = Modifier.width(Spacing.Small))
                        Text(
                            text = "Ready to review",
                            style = ArtifactTheme.typography.labelMedium,
                            color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.5f)
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Large)
                    ) {
                        com.saurabh.artifact.ui.components.ResonanceDisplay(
                            counts = com.saurabh.artifact.model.ArtifactReactionCounts(
                                artifactId = artifact.id,
                                totalCount = artifact.reactionCount,
                                visibility = artifact.reactionVisibility
                            ),
                            isOwner = isOwner
                        )

                        StatItem(
                            icon = Icons.Rounded.ChatBubbleOutline,
                            count = artifact.commentCount,
                            label = "responses"
                        )
                    }
                }

                Text(
                    text = formatDuration(artifact.durationMs),
                    style = ArtifactTheme.typography.labelSmall,
                    color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.4f)
                )
            }
        }
    }

    if (showMenu) {
        ArtifactManagementBottomSheet(
            isOwner = isOwner,
            isDraft = isDraft,
            isSaved = isSaved,
            onRenameClick = { showRenameDialog = true },
            onDeleteClick = { showDeleteDialog = true },
            onUnsaveClick = onUnsave,
            onViewCommentsClick = onViewComments,
            onDismiss = { showMenu = false }
        )
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

@Composable
private fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int,
    label: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$count $label",
            style = ArtifactTheme.typography.labelSmall,
            color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.6f)
        )
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%d:%02d".format(mins, secs)
}
