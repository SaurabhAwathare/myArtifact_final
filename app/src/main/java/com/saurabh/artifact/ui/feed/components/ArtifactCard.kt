package com.saurabh.artifact.ui.feed.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian900
import com.saurabh.artifact.ui.theme.Obsidian800

import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.ui.components.ArtifactAvatar

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtifactCard(
    artifact: Artifact,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    isSaved: Boolean = false,
    onPlayClick: () -> Unit,
    onReactionClick: (ReactionType) -> Unit,
    onCommentClick: () -> Unit,
    onSaveClick: () -> Unit,
    onReportClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(onClick = onPlayClick),
        colors = CardDefaults.cardColors(
            containerColor = Obsidian900
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // Top: Header (Anonymous Identity)
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtifactAvatar(
                    config = artifact.author.avatarConfig,
                    modifier = Modifier.size(40.dp),
                    isStatic = false // Keep it breathing if AURIC
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column {
                    Text(
                        text = artifact.author.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "2h ago", // Mock timestamp
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(GoldAura500.copy(alpha = 0.1f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = artifact.emotion.ifEmpty { "Neutral" },
                                style = MaterialTheme.typography.labelSmall,
                                color = GoldAura500.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = onReportClick, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Rounded.MoreVert,
                        contentDescription = "Options",
                        tint = Color.White.copy(alpha = 0.3f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Middle: Title & Waveform
            Text(
                text = artifact.title.ifEmpty { "A quiet moment shared..." },
                style = MaterialTheme.typography.headlineSmall,
                color = if (isPlaying) GoldAura500 else Color.White
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play/Pause/Buffering Control
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.05f))
                        .clickable { onPlayClick() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = GoldAura500
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = if (isPlaying) GoldAura500 else Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))
                
                // Placeholder for soft waveform
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .background(
                            if (isPlaying) GoldAura500.copy(alpha = 0.1f) 
                            else Color.White.copy(alpha = 0.05f), 
                            RoundedCornerShape(16.dp)
                        )
                ) {
                    // Minimalist Waveform Visualization (Mock)
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        repeat(20) { index ->
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height((10..24).random().dp)
                                    .background(
                                        if (isPlaying) GoldAura500.copy(alpha = 0.4f)
                                        else Color.White.copy(alpha = 0.1f),
                                        CircleShape
                                    )
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Text(
                    text = formatDuration(artifact.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Bottom Actions: Reactions (FlowRow)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReactionType.entries.take(4).forEach { type ->
                    ReactionChip(type.label, onClick = { onReactionClick(type) })
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Simple disabled icon without complex Tooltip for now to avoid compilation errors
                    IconButton(
                        onClick = onCommentClick,
                        enabled = false
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ChatBubbleOutline,
                            contentDescription = "Comments coming soon",
                            tint = Color.White.copy(alpha = 0.2f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSaveClick()
                    }) {
                        Icon(
                            imageVector = if (isSaved) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = if (isSaved) "Let go of reflection" else "Hold reflection",
                            tint = if (isSaved) GoldAura500 else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%d:%02d".format(mins, secs)
}

@Composable
fun ReactionChip(
    text: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}
