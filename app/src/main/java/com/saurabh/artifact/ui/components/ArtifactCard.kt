package com.saurabh.artifact.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.R
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactReactionCounts
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.ResolvedCreatorIdentity
import com.saurabh.artifact.util.ArtifactTitleFormatter
import com.saurabh.artifact.model.User
import com.saurabh.artifact.ui.components.motion.PressableScale
import com.saurabh.artifact.ui.feed.HydrationLevel
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.CardShape
import com.saurabh.artifact.ui.theme.LocalStartupStage
import com.saurabh.artifact.ui.theme.Spacing
import com.saurabh.artifact.ui.util.FeedbackUtils
import com.saurabh.artifact.util.TimeUtils

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
    isBuffering: Boolean = false,
    hydrationLevel: com.saurabh.artifact.ui.feed.HydrationLevel = com.saurabh.artifact.ui.feed.HydrationLevel.FULL,
    currentPosition: Long = 0,
    durationMs: Long = 0,
    onReportClick: () -> Unit = {},
    onFeedbackClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onAuthorClick: (String) -> Unit = {},
    onResonatorsCountClick: (String) -> Unit = {},
    currentUserId: String? = null,
    creatorProfile: User? = null,
    artifactDetail: com.saurabh.artifact.model.ArtifactDetail? = null,
    recommendationReason: com.saurabh.artifact.model.FeedRecommendationReason? = null,
) {
    if (hydrationLevel == com.saurabh.artifact.ui.feed.HydrationLevel.SHELL) {
        LightweightArtifactCard(artifact, onPlayClick, modifier)
        return
    }

    var showOptionsSheet by remember { mutableStateOf(false) }
    var showWhySheet by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    
    val isOwner = artifact.userId == currentUserId

    // Playback Haptic feedback
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val displayTitle = remember(artifact.title, artifact.episodeNumber) {
        ArtifactTitleFormatter.formatTitle(
            title = artifact.title,
            episodeNumber = artifact.episodeNumber,
            fallbackTitle = "A quiet moment shared..."
        )
    }
    
    val displayEmotionWithEmoji = remember(artifact.emotion) {
        val emotionEnum = com.saurabh.artifact.model.Emotion.entries.find { 
            it.label.equals(artifact.emotion, ignoreCase = true) || 
            it.name.equals(artifact.emotion, ignoreCase = true) 
        }
        if (emotionEnum != null) {
            "${emotionEnum.emoji} ${emotionEnum.label.lowercase()}"
        } else ""
    }

    val resolvedIdentity = remember(artifact, creatorProfile) {
        ResolvedCreatorIdentity.resolve(artifact, creatorProfile)
    }

    val isPending = artifact.audioUrl.isEmpty() && artifact.status == com.saurabh.artifact.model.ArtifactStatus.PENDING_UPLOAD

    val effectiveDurationMs = if (artifact.durationMs > 0) artifact.durationMs else durationMs

    val progress by remember(currentPosition, effectiveDurationMs) {
        derivedStateOf { if (effectiveDurationMs > 0) currentPosition.toFloat() / effectiveDurationMs else 0f }
    }

    val configuration = LocalConfiguration.current
    val formattedDuration = remember(effectiveDurationMs, configuration) {
        TimeUtils.formatCompactDurationMillis(effectiveDurationMs, configuration)
    }

    val playActionLabel = when {
        isBuffering -> stringResource(R.string.cd_buffering_playback)
        isPlaying -> stringResource(R.string.cd_pause_playback)
        else -> stringResource(R.string.cd_play_artifact)
    }

    // Atmospheric Waveform Height Animation - GUARDED BY isPlaying and hydrationLevel
    val waveformHeight = if (isPlaying && hydrationLevel >= com.saurabh.artifact.ui.feed.HydrationLevel.FULL) {
        animateDpAsState(
            targetValue = 64.dp,
            animationSpec = tween(500),
            label = "WaveformBloom"
        ).value
    } else {
        40.dp
    }

    // Moderation Shield
    val isHidden = artifact.moderation.status == com.saurabh.artifact.model.ModerationStatus.HIDDEN

    // Optimized Modifier Chain
    val cardModifier = modifier
        .fillMaxWidth()
        .clip(CardShape)
        .background(ArtifactTheme.colors.surfaceHearth)
        .border(
            width = 1.dp,
            color = ArtifactTheme.colors.waveformInactive,
            shape = CardShape
        )

    PressableScale(
        onClick = {
            if (isPending) {
                FeedbackUtils.explainDisabledAction(context, haptic, "This reflection is still settling in the archive...")
            } else {
                onPlayClick()
            }
        },
        enabled = !isHidden, // Allow interaction when pending for feedback
        scaleDownTo = 0.98f,
        modifier = cardModifier.alpha(if (isPending) 0.6f else 1f)
    ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Restrained Atmospheric Warmth: ONLY if playing and FULL hydration
                if (isPlaying && !isHidden && hydrationLevel >= com.saurabh.artifact.ui.feed.HydrationLevel.FULL) {
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isHidden) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.VisibilityOff, null, tint = ArtifactTheme.colors.statusError)
                                Spacer(Modifier.width(Spacing.Small))
                                Text("Content Hidden", style = ArtifactTheme.typography.labelMedium, color = ArtifactTheme.colors.statusError)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                ArtifactSigil(
                                    config = resolvedIdentity.sigilConfig,
                                    size = 32.dp,
                                    isStatic = true,
                                    modifier = Modifier.clickable { 
                                        if (resolvedIdentity.personaId.isNotBlank()) onAuthorClick(resolvedIdentity.personaId) 
                                    }
                                )
                                
                                Spacer(modifier = Modifier.width(12.dp))
                                
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = resolvedIdentity.name,
                                            modifier = Modifier
                                                .weight(1f, fill = false)
                                                .clickable { 
                                                    if (resolvedIdentity.personaId.isNotBlank()) onAuthorClick(resolvedIdentity.personaId) 
                                                },
                                            style = ArtifactTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 15.sp
                                            ),
                                            color = ArtifactTheme.colors.onSurfaceMain,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (resolvedIdentity.sigil.isNotEmpty()) {
                                            Text(
                                                text = " · ",
                                                style = ArtifactTheme.typography.labelMedium,
                                                color = ArtifactTheme.colors.onSurfaceMuted
                                            )
                                            Text(
                                                text = resolvedIdentity.sigil,
                                                style = ArtifactTheme.typography.labelSmall,
                                                color = ArtifactTheme.colors.onSurfaceMuted
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = TimeUtils.getRelativeTime(artifact.createdAt),
                                            style = ArtifactTheme.typography.labelSmall,
                                            color = ArtifactTheme.colors.onSurfaceMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isPending) {
                                            Spacer(modifier = Modifier.width(Spacing.Small))
                                            Text(
                                                text = "· Pending Upload...",
                                                style = ArtifactTheme.typography.labelSmall.copy(
                                                    fontStyle = FontStyle.Italic
                                                ),
                                                color = ArtifactTheme.colors.onSurfaceMuted,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                                
                                if (displayEmotionWithEmoji.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(Spacing.Small))
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
                                }
                            }
                        }

                        IconButton(
                            onClick = { showOptionsSheet = true },
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

                    Spacer(modifier = Modifier.height(Spacing.Medium))

                    Text(
                        text = if (isHidden) "This reflection has been quieted." else displayTitle,
                        style = ArtifactTheme.typography.titleLarge,
                        color = if (isHidden) ArtifactTheme.colors.onSurfaceMuted else ArtifactTheme.colors.onSurfaceMain,
                        fontStyle = if (isHidden) FontStyle.Italic else FontStyle.Normal,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!isHidden) {
                        Spacer(modifier = Modifier.height(Spacing.Large))

                        if (hydrationLevel >= com.saurabh.artifact.ui.feed.HydrationLevel.ENRICHED) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                IconButton(
                                    onClick = {
                                        if (isPending) {
                                            FeedbackUtils.explainDisabledAction(context, haptic, "This reflection is still settling in the archive...")
                                        } else {
                                            onPlayClick()
                                        }
                                    },
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

                                AmbientWaveform(
                                    amplitudes = artifactDetail?.amplitudeData?.takeIf { it.isNotEmpty() }
                                        ?: artifact.amplitudeData.takeIf { it.isNotEmpty() }
                                        ?: listOf(0.4f, 0.6f, 0.5f, 0.8f, 0.3f, 0.7f, 0.5f, 0.4f, 0.6f, 0.9f, 0.5f, 0.4f),
                                    progress = progress,
                                    modifier = Modifier.weight(1f).height(waveformHeight),
                                    isPaused = !isPlaying,
                                    isStatic = !isPlaying || hydrationLevel < com.saurabh.artifact.ui.feed.HydrationLevel.FULL,
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
                        } else {
                            // METADATA level: structural parity placeholder (prevents card height collapse)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val playLabel = stringResource(R.string.cd_play_artifact)
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
                                            contentDescription = playLabel
                                        }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = ArtifactTheme.colors.waveformActive,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(Spacing.Medium))

                                StaticWaveformPlaceholder(
                                    modifier = Modifier.weight(1f).height(40.dp),
                                    context = WaveformContext.Feed,
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
                        }

                        val effectiveReactionCounts = remember(artifact, artifactDetail) {
                            val detailCounts = artifactDetail?.reactionCounts
                            if (detailCounts != null && detailCounts.totalCount > 0) {
                                detailCounts
                            } else {
                                ArtifactReactionCounts(
                                    artifactId = artifact.id,
                                    totalCount = artifact.reactionCount,
                                    visibility = artifact.reactionVisibility
                                )
                            }
                        }

                        if (hydrationLevel >= HydrationLevel.METADATA &&
                            effectiveReactionCounts.totalCount > 0
                        ) {
                            Spacer(modifier = Modifier.height(Spacing.ExtraSmall))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ResonanceDisplay(
                                    counts = effectiveReactionCounts,
                                    isOwner = isOwner,
                                    onClick = { onResonatorsCountClick(artifact.id) }
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
            isPublic = artifact.isPublic,
            isDraft = artifact.isDraft,
            onReportClick = onReportClick,
            onDismiss = { showOptionsSheet = false },
            showDelete = false,
            showSettings = true,
            onFeedbackClick = onFeedbackClick,
            onSettingsClick = onSettingsClick,
            onWhyThisClick = { showWhySheet = true },
            recommendationReason = recommendationReason,
            onShareClick = {
                val payload = com.saurabh.artifact.model.SharePayload(
                    artifactId = artifact.id,
                    title = artifact.title,
                    authorName = resolvedIdentity.name,
                    authorSigil = resolvedIdentity.sigil,
                    shareUrl = com.saurabh.artifact.util.ShareUrlGenerator.generateArtifactUrl(artifact.id)
                )
                val shareText = com.saurabh.artifact.util.ShareFormatter.formatShareText(payload)
                
                val sendIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                    type = "text/plain"
                }
                val shareIntent = android.content.Intent.createChooser(sendIntent, null)
                context.startActivity(shareIntent)
            }
        )
    }

    if (showWhySheet && recommendationReason != null) {
        RecommendationExplanationSheet(
            reason = recommendationReason,
            onDismiss = { showWhySheet = false }
        )
    }
}

/**
 * An extremely optimized, static-only version of the ArtifactCard for the first frame (SHELL hydration).
 * Maintains structural and height parity with hydrated states to prevent layout shifts.
 */
@Composable
private fun LightweightArtifactCard(
    artifact: Artifact,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayTitle = remember(artifact.title, artifact.episodeNumber) {
        ArtifactTitleFormatter.formatTitle(
            title = artifact.title,
            episodeNumber = artifact.episodeNumber,
            fallbackTitle = "A quiet moment shared..."
        )
    }

    val displayEmotionWithEmoji = remember(artifact.emotion) {
        val emotionEnum = com.saurabh.artifact.model.Emotion.entries.find { 
            it.label.equals(artifact.emotion, ignoreCase = true) || 
            it.name.equals(artifact.emotion, ignoreCase = true) 
        }
        if (emotionEnum != null) {
            "${emotionEnum.emoji} ${emotionEnum.label.lowercase()}"
        } else ""
    }

    val resolvedIdentity = remember(artifact) {
        ResolvedCreatorIdentity.resolve(artifact, null)
    }

    val configuration = LocalConfiguration.current
    val effectiveDurationMs = artifact.durationMs
    val formattedDuration = remember(effectiveDurationMs, configuration) {
        TimeUtils.formatCompactDurationMillis(effectiveDurationMs, configuration)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(ArtifactTheme.colors.surfaceHearth)
            .border(
                width = 1.dp,
                color = ArtifactTheme.colors.waveformInactive,
                shape = CardShape
            )
            .padding(Spacing.Large)
    ) {
        Column {
            // Header Row: Sigil + Author Info + Emotion Chip + More Options reservation
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    ArtifactSigil(
                        config = resolvedIdentity.sigilConfig,
                        size = 32.dp,
                        isStatic = true
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = resolvedIdentity.name,
                                modifier = Modifier.weight(1f, fill = false),
                                style = ArtifactTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp
                                ),
                                color = ArtifactTheme.colors.onSurfaceMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (resolvedIdentity.sigil.isNotEmpty()) {
                                Text(
                                    text = " · ",
                                    style = ArtifactTheme.typography.labelMedium,
                                    color = ArtifactTheme.colors.onSurfaceMuted
                                )
                                Text(
                                    text = resolvedIdentity.sigil,
                                    style = ArtifactTheme.typography.labelSmall,
                                    color = ArtifactTheme.colors.onSurfaceMuted
                                )
                            }
                        }
                        Text(
                            text = TimeUtils.getRelativeTime(artifact.createdAt),
                            style = ArtifactTheme.typography.labelSmall,
                            color = ArtifactTheme.colors.onSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    if (displayEmotionWithEmoji.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(Spacing.Small))
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
                    }
                }

                // 48dp target for layout reservation
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = null,
                        tint = ArtifactTheme.colors.onSurfaceMuted,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.Medium))

            // Title: maxLines = 3 ensures identical height reservation for multi-line titles
            Text(
                text = displayTitle,
                style = ArtifactTheme.typography.titleLarge,
                color = ArtifactTheme.colors.onSurfaceMain,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(Spacing.Large))

            // Playback Row: 48dp Play Button + 40dp Static Waveform + Duration
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                val playLabel = stringResource(R.string.cd_play_artifact)
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
                            contentDescription = playLabel
                        }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = ArtifactTheme.colors.waveformActive,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.Medium))

                StaticWaveformPlaceholder(
                    modifier = Modifier.weight(1f).height(40.dp),
                    context = WaveformContext.Feed,
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

            // Resonance Row parity when reactionCount > 0
            if (artifact.reactionCount > 0) {
                Spacer(modifier = Modifier.height(Spacing.ExtraSmall))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ResonanceDisplay(
                        counts = ArtifactReactionCounts(
                            artifactId = artifact.id,
                            totalCount = artifact.reactionCount,
                            visibility = artifact.reactionVisibility
                        ),
                        isOwner = false,
                        onClick = {}
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505, widthDp = 320)
@Composable
fun PreviewArtifactCardNarrow() {
    androidx.compose.runtime.CompositionLocalProvider(LocalStartupStage provides com.saurabh.artifact.startup.StartupStage.STABLE) {
        ArtifactTheme {
            val mockArtifact = Artifact(
                id = "1",
                userId = "user_1",
                author = AuthorSnapshot(
                    name = "DeepThinker",
                    sigil = "◈"
                ),
                title = "Testing long username on narrow screen.",
                audioUrl = "",
                durationMs = 95000,
                emotion = "Overwhelmed"
            )
            ArtifactCard(
                artifact = mockArtifact,
                isPlaying = false,
                onPlayClick = {},
                modifier = Modifier.padding(Spacing.Medium)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun PreviewArtifactCardWorstCase() {
    androidx.compose.runtime.CompositionLocalProvider(LocalStartupStage provides com.saurabh.artifact.startup.StartupStage.STABLE) {
        ArtifactTheme {
            val mockArtifact = Artifact(
                id = "1",
                userId = "user_1",
                author = AuthorSnapshot(
                    name = "DeepThinker",
                    sigil = "◈"
                ),
                title = "Worst case scenario with all elements present and very long content.",
                audioUrl = "",
                durationMs = 102000,
                emotion = "Overwhelmed"
            )
            ArtifactCard(
                artifact = mockArtifact,
                isPlaying = false,
                onPlayClick = {},
                modifier = Modifier.padding(Spacing.Medium)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun PreviewArtifactCardAtmospheric() {
    androidx.compose.runtime.CompositionLocalProvider(LocalStartupStage provides com.saurabh.artifact.startup.StartupStage.STABLE) {
        ArtifactTheme {
            val mockArtifact = Artifact(
                id = "1",
                userId = "user_1",
                author = AuthorSnapshot(name = "QuietLoom"),
                title = "A reflection on the evening rain and the sound of silence.",
                audioUrl = "",
                durationMs = 120000,
                emotion = "Hopeful",
                reactionCount = 12
            )
            ArtifactCard(
                artifact = mockArtifact,
                isPlaying = true,
                onPlayClick = {},
                currentPosition = 30000,
                durationMs = 120000,
                currentUserId = "user_1",
                modifier = Modifier.padding(vertical = Spacing.Medium)
            )
        }
    }
}
