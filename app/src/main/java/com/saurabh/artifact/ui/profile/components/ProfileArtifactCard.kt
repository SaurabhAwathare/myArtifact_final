package com.saurabh.artifact.ui.profile.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import com.saurabh.artifact.R
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactReactionCounts
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.model.SigilConfig
import com.saurabh.artifact.ui.components.AmbientWaveform
import com.saurabh.artifact.ui.components.ArtifactSigil
import com.saurabh.artifact.ui.components.ResonanceDisplay
import com.saurabh.artifact.ui.components.WaveformContext
import com.saurabh.artifact.ui.player.components.ModerationBanner
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.CardShape
import com.saurabh.artifact.ui.theme.LocalStartupStage
import com.saurabh.artifact.ui.theme.Spacing
import com.saurabh.artifact.util.ArtifactTitleFormatter
import com.saurabh.artifact.util.TimeUtils
import java.text.SimpleDateFormat

/**
 * A specialized card for the Profile screen that emphasizes "intentional emotional ownership".
 * Redesigned in Stage 3.3 to adhere to the Artifact Design System:
 * - 16dp CardShape geometry
 * - Obsidian800 (surfaceHearth) solid surface
 * - Obsidian700 (waveformInactive) 1.dp subtle outline
 * - Manrope typography with high-contrast ReflectionWhite and MistGray metadata (WCAG AA compliant)
 * - 48dp minimum interactive touch targets (play/pause and overflow actions)
 * - Restrained Golden Aura atmospheric warmth during active playback
 * - Preserves all existing management, rename, delete, and resonance navigation behaviors
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
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        ConfigurationCompat.getLocales(configuration)[0] ?: configuration.locales[0]
    }
    val dateFormat = remember(locale) { SimpleDateFormat("MMM d, yyyy", locale) }
    val displayDate = remember(artifact.createdAt, dateFormat) {
        dateFormat.format(artifact.createdAt.toDate())
    }

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

    val effectiveDurationMs = artifact.durationMs
    val formattedDuration = remember(effectiveDurationMs, configuration) {
        TimeUtils.formatCompactDurationMillis(effectiveDurationMs, configuration)
    }

    val playActionLabel = when {
        isBuffering -> stringResource(R.string.cd_buffering_playback)
        isPlaying -> stringResource(R.string.cd_pause_playback)
        else -> stringResource(R.string.cd_play_artifact)
    }

    val displayTitle = remember(artifact.title, artifact.episodeNumber) {
        ArtifactTitleFormatter.formatTitle(
            title = artifact.title,
            episodeNumber = artifact.episodeNumber,
            fallbackTitle = "Untitled Moment"
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Small),
        shape = CardShape,
        color = ArtifactTheme.colors.surfaceHearth,
        border = BorderStroke(
            width = 1.dp,
            color = ArtifactTheme.colors.waveformInactive
        ),
        onClick = onPlayClick
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Restrained Atmospheric Warmth: active only during playback
            if (isPlaying) {
                val activeColor = ArtifactTheme.colors.waveformActive
                val radialWarmth = remember(activeColor) {
                    Brush.radialGradient(
                        colors = listOf(activeColor.copy(alpha = 0.07f), Color.Transparent),
                        radius = 450f
                    )
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(radialWarmth)
                )
            }

            Column(modifier = Modifier.padding(Spacing.Large)) {
                if (isOwner) {
                    ModerationBanner(
                        recommendationState = artifact.recommendationState,
                        modifier = Modifier.padding(bottom = Spacing.Large)
                    )
                }

                // Top: Identity context (if saved/other), Title, Date and Management Menu
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        // Public presentation identity for saved or external reflections
                        if (!isOwner && !isDraft && artifact.author.name.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 6.dp)
                            ) {
                                ArtifactSigil(
                                    config = artifact.author.sigilConfig,
                                    size = 20.dp,
                                    isStatic = true
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = artifact.author.name,
                                    style = ArtifactTheme.typography.titleSmall.copy(
                                        fontSize = 14.sp
                                    ),
                                    color = ArtifactTheme.colors.onSurfaceMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (artifact.author.sigil.isNotEmpty()) {
                                    Text(
                                        text = " · ${artifact.author.sigil}",
                                        style = ArtifactTheme.typography.labelSmall,
                                        color = ArtifactTheme.colors.onSurfaceMuted,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Text(
                            text = displayTitle,
                            style = ArtifactTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp
                            ),
                            color = ArtifactTheme.colors.onSurfaceMain,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = displayDate,
                            style = ArtifactTheme.typography.labelSmall,
                            color = ArtifactTheme.colors.onSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(Spacing.Small))

                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.cd_more_options),
                            tint = ArtifactTheme.colors.onSurfaceMuted,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.Large))

                // Center: Audio Controls & Waveform
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 48.dp Circular Play/Pause/Buffering Control
                    IconButton(
                        onClick = onPlayClick,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(ArtifactTheme.colors.surfaceLoom)
                            .border(
                                width = 1.dp,
                                color = ArtifactTheme.colors.waveformInactive.copy(alpha = 0.6f),
                                shape = CircleShape
                            )
                            .semantics {
                                contentDescription = playActionLabel
                            }
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

                    // Ambient Waveform with 40dp height parity
                    AmbientWaveform(
                        amplitudes = artifact.amplitudeData.takeIf { it.isNotEmpty() }
                            ?: listOf(0.4f, 0.6f, 0.5f, 0.8f, 0.3f, 0.7f, 0.5f, 0.4f, 0.6f, 0.9f, 0.5f, 0.4f),
                        progress = progress,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        isPaused = !isPlaying,
                        isStatic = !isPlaying,
                        context = if (isPlaying) WaveformContext.Player else WaveformContext.Feed,
                        id = artifact.id
                    )

                    if (formattedDuration != null) {
                        Spacer(modifier = Modifier.width(Spacing.Medium))
                        Text(
                            text = formattedDuration,
                            style = ArtifactTheme.typography.labelSmall,
                            color = ArtifactTheme.colors.onSurfaceMuted,
                            maxLines = 1
                        )
                    }
                }

                // Bottom Row: Emotion Tag (Bottom-Left) & Contextual Metadata (Bottom-Right)
                val showBottomRow = displayEmotionWithEmoji.isNotEmpty() || isDraft || artifact.reactionCount > 0
                if (showBottomRow) {
                    Spacer(modifier = Modifier.height(Spacing.Medium))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Bottom-Left: Emotion Tag Chip
                        if (displayEmotionWithEmoji.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .background(ArtifactTheme.colors.surfaceLoom)
                                    .border(
                                        width = 1.dp,
                                        color = ArtifactTheme.colors.waveformInactive.copy(alpha = 0.5f),
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                    .semantics {
                                        contentDescription = "Emotion: ${artifact.emotion}"
                                    }
                            ) {
                                Text(
                                    text = displayEmotionWithEmoji,
                                    style = ArtifactTheme.typography.labelSmall,
                                    color = ArtifactTheme.colors.onSurfaceAura,
                                    maxLines = 1
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
                                        .background(if (isListened) ArtifactTheme.colors.waveformActive else ArtifactTheme.colors.waveformInactive)
                                )
                                Spacer(modifier = Modifier.width(Spacing.Small))
                                Text(
                                    text = if (isListened) "Ready to publish" else "Review to publish (${(reviewProgress * 100).toInt()}%)",
                                    style = ArtifactTheme.typography.labelMedium,
                                    color = ArtifactTheme.colors.onSurfaceMuted
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

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun PreviewProfileArtifactCardDefault() {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStartupStage provides com.saurabh.artifact.startup.StartupStage.STABLE
    ) {
        ArtifactTheme {
            val mockArtifact = Artifact(
                id = "p1",
                userId = "user_self",
                title = "A quiet reflection on returning home",
                durationMs = 84000,
                emotion = "peaceful",
                reactionCount = 5
            )
            ProfileArtifactCard(
                artifact = mockArtifact,
                isDraft = false,
                isOwner = true,
                isPlaying = false,
                onPlayClick = {},
                onRename = {},
                onDelete = {},
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun PreviewProfileArtifactCardPlaying() {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStartupStage provides com.saurabh.artifact.startup.StartupStage.STABLE
    ) {
        ArtifactTheme {
            val mockArtifact = Artifact(
                id = "p1_playing",
                userId = "user_self",
                title = "The warmth of evening thoughts and silent gratitude",
                durationMs = 120000,
                emotion = "grateful",
                reactionCount = 12
            )
            ProfileArtifactCard(
                artifact = mockArtifact,
                isDraft = false,
                isOwner = true,
                isPlaying = true,
                progress = 0.45f,
                onPlayClick = {},
                onRename = {},
                onDelete = {},
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505, widthDp = 320)
@Composable
fun PreviewProfileArtifactCardNarrow() {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStartupStage provides com.saurabh.artifact.startup.StartupStage.STABLE
    ) {
        ArtifactTheme {
            val mockArtifact = Artifact(
                id = "p2",
                userId = "user_self",
                title = "Testing long title on narrow screen with multiple lines of text",
                durationMs = 145000,
                emotion = "overwhelmed",
                reactionCount = 18
            )
            ProfileArtifactCard(
                artifact = mockArtifact,
                isDraft = false,
                isOwner = true,
                isPlaying = false,
                onPlayClick = {},
                onRename = {},
                onDelete = {},
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun PreviewProfileArtifactCardDraft() {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStartupStage provides com.saurabh.artifact.startup.StartupStage.STABLE
    ) {
        ArtifactTheme {
            val mockArtifact = Artifact(
                id = "p_draft",
                userId = "user_self",
                title = "Unpublished voice note from mountain walk",
                durationMs = 62000,
                emotion = "wonder"
            )
            ProfileArtifactCard(
                artifact = mockArtifact,
                isDraft = true,
                isOwner = true,
                isPlaying = false,
                reviewProgress = 0.65f,
                isListened = false,
                onPlayClick = {},
                onRename = {},
                onDelete = {},
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun PreviewProfileArtifactCardSaved() {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStartupStage provides com.saurabh.artifact.startup.StartupStage.STABLE
    ) {
        ArtifactTheme {
            val mockArtifact = Artifact(
                id = "p_saved",
                userId = "other_user",
                author = AuthorSnapshot(
                    anonymousId = "other_user",
                    name = "SilentWanderer",
                    sigil = "✦",
                    sigilConfig = SigilConfig(seed = "other_user")
                ),
                title = "A stranger's reflection on finding calm in chaos",
                durationMs = 95000,
                emotion = "serene",
                reactionCount = 42
            )
            ProfileArtifactCard(
                artifact = mockArtifact,
                isDraft = false,
                isOwner = false,
                isSaved = true,
                isPlaying = false,
                onPlayClick = {},
                onRename = {},
                onDelete = {},
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
