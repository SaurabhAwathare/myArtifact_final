package com.saurabh.artifact.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.FeedbackType
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.ui.components.motion.PressableScale
import com.saurabh.artifact.ui.components.TextCommentItem
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing

/**
 * Utility to map an emotion string to its representative emoji.
 */
fun getEmotionEmoji(emotion: String): String {
    return EmotionList.find { it.label.equals(emotion, ignoreCase = true) }?.emoji ?: "✨"
}

/**
 * A premium, emotionally-focused card for displaying artifacts in the feed.
 * Supports a Compact mode for Profile sections to improve information density.
 * 
 * Performance Refactor: Now supports isHydrated flag to defer expensive UI elements.
 */
@Composable
fun ArtifactCard(
    artifact: Artifact,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    isBuffering: Boolean = false,
    hydrationLevel: com.saurabh.artifact.ui.feed.HydrationLevel = com.saurabh.artifact.ui.feed.HydrationLevel.FULL,
    currentPosition: Long = 0,
    duration: Long = 0,
    onReportClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    currentUserId: String? = null
) {
    if (hydrationLevel == com.saurabh.artifact.ui.feed.HydrationLevel.SHELL && !isCompact) {
        LightweightArtifactCard(artifact, onPlayClick, modifier)
        return
    }

    val isHydrated = hydrationLevel >= com.saurabh.artifact.ui.feed.HydrationLevel.METADATA
    val stage = com.saurabh.artifact.ui.theme.ArtifactTheme.stage

    var showOptionsSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    
    val currentUser = com.saurabh.artifact.ui.theme.ArtifactTheme.currentUser
    val isOwner = artifact.userId == (currentUserId ?: currentUser?.id)

    // Dynamic identity resolution: prefer live profile for the current user to fix sync issues
    val displayArtifact = remember(artifact, currentUser) {
        if (isOwner && currentUser != null) {
            artifact.copy(
                author = artifact.author.copy(
                    avatarConfig = currentUser.avatarConfig,
                    name = currentUser.anonymousName.ifEmpty { artifact.author.name },
                    sigil = currentUser.anonymousSigil.ifEmpty { artifact.author.sigil }
                )
            )
        } else {
            artifact
        }
    }

    // Logic to handle on-demand fetching when expanded
    // LaunchedEffect(showReplies) { ... } // Removed since interaction row is gone

    // Playback Haptic feedback
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val displayTitle = remember(displayArtifact.title) { displayArtifact.title.ifEmpty { "A quiet moment shared..." } }
    val displayEmotion = remember(displayArtifact.emotion) { displayArtifact.emotion.ifEmpty { "reflective" }.lowercase() }
    val displayUsername = remember(displayArtifact.author.name) { displayArtifact.author.name.ifEmpty { "anonymous soul" }.lowercase() }

    val progress by remember(currentPosition, duration) {
        derivedStateOf { if (duration > 0) currentPosition.toFloat() / duration else 0f }
    }

    // Atmospheric Waveform Height Animation - GUARDED BY isPlaying and hydrationLevel
    val waveformHeight = if (isPlaying && hydrationLevel >= com.saurabh.artifact.ui.feed.HydrationLevel.FULL) {
        animateDpAsState(
            targetValue = if (isCompact) 32.dp else 64.dp,
            animationSpec = tween(500),
            label = "WaveformBloom"
        ).value
    } else {
        if (isCompact) 32.dp else 40.dp
    }

    // Moderation Shield
    val isHidden = displayArtifact.moderation.status == com.saurabh.artifact.model.ModerationStatus.HIDDEN

    if (isCompact) {
        CompactArtifactItem(
            artifact = displayArtifact,
            displayTitle = if (isHidden) "Content Hidden" else displayTitle,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            progress = progress,
            onPlayClick = if (isHidden) ({}) else onPlayClick,
            modifier = modifier
        )
    } else {
        // Optimized Modifier Chain
        val cardModifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Medium)
            .clip(MaterialTheme.shapes.large)
            .background(ArtifactTheme.colors.surfaceHearth)
            .let { 
                if (isHydrated) {
                    it.border(
                        width = 0.5.dp,
                        color = ArtifactTheme.colors.waveformInactive.copy(alpha = 0.1f),
                        shape = MaterialTheme.shapes.large
                    )
                } else it
            }

        PressableScale(
            onClick = if (isHidden) ({}) else onPlayClick,
            scaleDownTo = 0.98f,
            modifier = cardModifier
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Expensive Background Effect: ONLY if playing and FULL hydration
                if (isPlaying && !isHidden && hydrationLevel >= com.saurabh.artifact.ui.feed.HydrationLevel.FULL) {
                    val activeColor = ArtifactTheme.colors.waveformActive
                    val radialGradient = remember(activeColor) {
                        Brush.radialGradient(
                            colors = listOf(activeColor, Color.Transparent),
                            radius = 350f
                        )
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(0.12f)
                            .background(radialGradient)
                    )
                }

                Column(modifier = Modifier.padding(Spacing.Large)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isHidden) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.VisibilityOff, null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(Spacing.Small))
                                Text("Content Hidden", style = ArtifactTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                AuricAvatar(
                                    seed = displayArtifact.author.avatarConfig.seed
                                        .ifEmpty { displayArtifact.author.avatarSeed }
                                        .ifEmpty { displayArtifact.userId },
                                    size = 32.dp
                                )
                                
                                Spacer(modifier = Modifier.width(Spacing.Medium))
                                
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = displayUsername,
                                            style = ArtifactTheme.typography.labelLarge.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 15.sp
                                            ),
                                            color = ArtifactTheme.colors.onSurfaceMain
                                        )
                                        if (displayArtifact.author.sigil.isNotEmpty()) {
                                            Text(
                                                text = " · ",
                                                style = ArtifactTheme.typography.labelMedium,
                                                color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.5f)
                                            )
                                            Text(
                                                text = displayArtifact.author.sigil,
                                                style = ArtifactTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Light
                                                ),
                                                color = ArtifactTheme.colors.onSurfaceMuted
                                            )
                                        }
                                    }
                                    Text(
                                        text = "2h ago", // Mock timestamp for now
                                        style = ArtifactTheme.typography.labelSmall,
                                        color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.6f)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(Spacing.Large))
                                
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(ArtifactTheme.colors.waveformActive.copy(alpha = 0.05f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = displayEmotion,
                                        style = ArtifactTheme.typography.labelSmall,
                                        color = ArtifactTheme.colors.waveformActive.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = { showOptionsSheet = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "Options",
                                tint = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.4f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.Medium))

                    Text(
                        text = if (isHidden) "This reflection has been quieted." else displayTitle,
                        style = ArtifactTheme.typography.bodyLarge,
                        color = if (isHidden) ArtifactTheme.colors.onSurfaceMuted else ArtifactTheme.colors.onSurfaceMain,
                        fontStyle = if (isHidden) FontStyle.Italic else FontStyle.Normal
                    )

                    if (!isHidden) {
                        if (hydrationLevel >= com.saurabh.artifact.ui.feed.HydrationLevel.ENRICHED) {
                            Spacer(modifier = Modifier.height(Spacing.Large))

                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Atmospheric Glow (Disabled blur in feed for performance)
                                if (isPlaying && hydrationLevel >= com.saurabh.artifact.ui.feed.HydrationLevel.FULL) {
                                    val activeColor = ArtifactTheme.colors.waveformActive
                                    val glowGradient = remember(activeColor) {
                                        Brush.horizontalGradient(
                                            listOf(Color.Transparent, activeColor, Color.Transparent)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(waveformHeight)
                                            .alpha(0.1f)
                                            .background(glowGradient)
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    IconButton(
                                        onClick = onPlayClick,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(ArtifactTheme.colors.waveformActive.copy(alpha = 0.08f))
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
                                                contentDescription = null,
                                                tint = ArtifactTheme.colors.waveformActive,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(Spacing.Medium))

                                    AmbientWaveform(
                                        amplitudes = displayArtifact.amplitudeData.takeIf { it.isNotEmpty() } ?: listOf(0.4f, 0.6f, 0.5f, 0.8f, 0.3f, 0.7f, 0.5f, 0.4f, 0.6f, 0.9f, 0.5f, 0.4f),
                                        progress = progress,
                                        modifier = Modifier.weight(1f).height(waveformHeight),
                                        isPaused = !isPlaying,
                                        isStatic = !isPlaying || hydrationLevel < com.saurabh.artifact.ui.feed.HydrationLevel.FULL,
                                        context = if (isPlaying) WaveformContext.Player else WaveformContext.Feed
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(Spacing.Small))

                        if (hydrationLevel >= com.saurabh.artifact.ui.feed.HydrationLevel.METADATA && stage >= com.saurabh.artifact.startup.StartupStage.IMMERSION) {
                            ResonanceDisplay(
                                counts = com.saurabh.artifact.model.ArtifactReactionCounts(
                                    artifactId = displayArtifact.id,
                                    totalCount = displayArtifact.reactionCount,
                                    visibility = displayArtifact.reactionVisibility
                                ),
                                isOwner = isOwner
                            )
                        }
                    }
                }
            }
        }
    }

    if (showOptionsSheet) {
        ArtifactOptionsSheet(
            isOwner = isOwner,
            onReportClick = onReportClick,
            onDismiss = { showOptionsSheet = false },
            onDeleteClick = { showDeleteConfirm = true }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Reflection?") },
            text = { Text("This will permanently remove this reflection and all associated resonances. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteClick()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * An extremely optimized, static-only version of the ArtifactCard for the first frame.
 */
@Composable
private fun LightweightArtifactCard(
    artifact: Artifact,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Medium)
            .clip(MaterialTheme.shapes.large)
            .background(ArtifactTheme.colors.surfaceHearth)
            .padding(Spacing.Large)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(ArtifactTheme.colors.waveformActive.copy(alpha = 0.2f)))
                Spacer(Modifier.width(Spacing.Small))
                Text(
                    text = artifact.emotion.ifEmpty { "reflective" }.lowercase(),
                    style = ArtifactTheme.typography.labelLarge,
                    color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.4f)
                )
            }

            Spacer(modifier = Modifier.height(Spacing.Medium))

            Text(
                text = artifact.title.ifEmpty { "A quiet moment shared..." },
                style = ArtifactTheme.typography.bodyLarge,
                color = ArtifactTheme.colors.onSurfaceMain.copy(alpha = 0.7f),
                maxLines = 1, // Minimize text traversal
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(Spacing.Large))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Fixed Play Button (No state/animation)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(ArtifactTheme.colors.waveformActive.copy(alpha = 0.03f))
                        .clickable(onClick = onPlayClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow, 
                        contentDescription = null, 
                        tint = ArtifactTheme.colors.waveformActive.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.Medium))

                // Placeholder Waveform (Static)
                StaticWaveformPlaceholder(
                    modifier = Modifier.weight(1f),
                    context = WaveformContext.Feed
                )
            }
        }
    }
}

@Composable
private fun CompactArtifactItem(
    artifact: Artifact,
    displayTitle: String,
    isPlaying: Boolean,
    isBuffering: Boolean,
    progress: Float,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = ArtifactTheme.colors.surfaceHearth.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPlayClick,
                modifier = Modifier
                    .size(36.dp)
                    .background(ArtifactTheme.colors.waveformActive.copy(alpha = 0.1f), CircleShape)
            ) {
                if (isBuffering) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ArtifactTheme.colors.waveformActive)
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = ArtifactTheme.colors.waveformActive,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle,
                    style = ArtifactTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ArtifactTheme.colors.onSurfaceMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    AmbientWaveform(
                        amplitudes = artifact.amplitudeData.takeIf { it.isNotEmpty() } ?: listOf(0.4f, 0.6f, 0.3f, 0.8f, 0.5f, 0.7f, 0.4f, 0.6f),
                        progress = progress,
                        modifier = Modifier.weight(1f).height(12.dp),
                        isPaused = !isPlaying,
                        context = WaveformContext.Mini
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatDuration(artifact.duration),
                        style = ArtifactTheme.typography.labelSmall,
                        color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun PreviewArtifactCardAtmospheric() {
    ArtifactTheme {
        val mockArtifact = Artifact(
            id = "1",
            userId = "user_1",
            author = AuthorSnapshot(name = "QuietLoom"),
            title = "A reflection on the evening rain and the sound of silence.",
            audioUrl = "",
            duration = 120,
            emotion = "Peaceful"
        )
        ArtifactCard(
            artifact = mockArtifact,
            isPlaying = true,
            onPlayClick = {},
            currentPosition = 30000,
            duration = 120000,
            currentUserId = "user_1"
        )
    }
}
