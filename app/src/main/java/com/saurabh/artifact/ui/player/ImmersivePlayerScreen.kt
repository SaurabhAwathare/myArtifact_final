package com.saurabh.artifact.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.saurabh.artifact.model.PlayableArtifact
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.ResolvedCreatorIdentity
import com.saurabh.artifact.model.avatar.AvatarConfiguration
import com.saurabh.artifact.ui.components.ArtifactAvatar
import com.saurabh.artifact.ui.components.EmotionTagsGroup
import com.saurabh.artifact.ui.player.components.*
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.theme.ModalShape
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.util.ArtifactTitleFormatter
import com.saurabh.artifact.ui.player.components.ReviewInteractionLayer
import kotlin.math.roundToInt

/**
 * ImmersivePlayerScreen - The unified cinematic listening and draft review space.
 */
@Composable
fun ImmersivePlayerScreen(
    artifact: PlayerArtifact?,
    playableArtifact: PlayableArtifact?,
    uiState: PlayerUiState,
    reviewInteractionState: ReviewInteractionUiState,
    onCollapse: () -> Unit,
    onTogglePlayback: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    onScrubbing: (Float) -> Unit = {},
    onResonateClick: (com.saurabh.artifact.model.ReactionType) -> Unit = {},
    onResonateConnectionClick: () -> Unit = {},
    onResonatorsCountClick: (String) -> Unit = {},
    onSaveClick: () -> Unit = {},
    onEditClick: () -> Unit = {},
    onPublishClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onCommentClick: () -> Unit = {},
    onMoreClick: () -> Unit = {},
    onAuthorClick: (String) -> Unit = {}
) {
    var showTranscript by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    // 0. System Back Handling
    BackHandler {
        onCollapse()
    }
    
    // Gesture State for swipe-down to collapse
    var offsetY by remember { mutableFloatStateOf(0f) }
    val scrollState = rememberScrollState()

    // Phase 4: Harden Draft Detection (Unified Logic)
    val isVerifiedDraft = artifact?.isDraft == true

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Draft?", color = Color.White) },
            text = { Text("This will permanently remove this private reflection and local audio recording from this device.", color = Color.White.copy(alpha = 0.7f)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteClick()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = ArtifactTheme.colors.actionDestructive)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.5f))
                }
            },
            shape = ModalShape,
            containerColor = ArtifactTheme.colors.surfaceHearth
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian950)
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { /* EXPLICIT TOUCH INTERCEPTION: Prevent pass-through to underlying Feed/Screens */ }
            )
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { /* Optional haptic */ },
                    onVerticalDrag = { change, dragAmount ->
                        // Only intercept when dragging downwards at the top of scroll or while already dragged
                        if ((offsetY > 0f) || (scrollState.value == 0 && dragAmount > 0f)) {
                            change.consume()
                            offsetY = (offsetY + dragAmount).coerceAtLeast(0f)
                        }
                    },
                    onDragEnd = {
                        if (offsetY > 300f) {
                            onCollapse()
                        }
                        offsetY = 0f
                    },
                    onDragCancel = {
                        offsetY = 0f
                    }
                )
            }
    ) {
        // 1. Emotional Background Engine
        val currentEmotion = playableArtifact?.emotion ?: artifact?.emotion ?: ""
        EmotionalBackground(
            emotion = currentEmotion,
            modifier = Modifier.fillMaxSize()
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            val availableHeight = maxHeight
            val availableWidth = maxWidth
            val isCompactWidth = availableWidth < 360.dp
            val isCompactHeight = availableHeight < 640.dp
            val horizontalPadding = if (isCompactWidth) 16.dp else 24.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = availableHeight)
                        .padding(horizontal = horizontalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = if (isCompactHeight) Arrangement.spacedBy(8.dp) else Arrangement.SpaceBetween
                ) {
                    // 1. Header (Isolated for performance) & Moderation Banner (Creator Only)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PlayerHeader(
                            artifact = artifact,
                            showTranscript = showTranscript,
                            isVerifiedDraft = isVerifiedDraft,
                            onCollapse = onCollapse,
                            onToggleTranscript = { showTranscript = !showTranscript },
                            onMoreClick = onMoreClick,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )

                        if (uiState.isOwner) {
                            ModerationBanner(
                                recommendationState = uiState.recommendationState,
                                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                            )
                        }
                    }

                    // 2. Centered Identity Section (or Transcript) — Phase 2.18 Approved Visual Redesign
                    if (showTranscript) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (isCompactHeight) 140.dp else 190.dp)
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            TranscriptOverlay(
                                segments = artifact?.transcript ?: emptyList(),
                                currentPosition = uiState.currentPosition,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                val avatarDimension = when {
                                    isCompactHeight -> 72.dp
                                    availableHeight < 800.dp -> 88.dp
                                    else -> 100.dp
                                }

                                // 2.1 Smaller Centered Avatar with subtle halo
                                Box(
                                    modifier = Modifier.size(avatarDimension + 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val emotion = playableArtifact?.emotion ?: artifact?.emotion ?: ""

                                    EmotionalAudioSurface(
                                        emotion = emotion,
                                        isPlaying = uiState.isPlaying,
                                        modifier = Modifier
                                            .size(avatarDimension + 16.dp)
                                            .alpha(0.35f)
                                    )

                                    if (isVerifiedDraft) {
                                        EmotionalAudioSurface(
                                            emotion = emotion,
                                            isPlaying = uiState.isPlaying,
                                            modifier = Modifier.size(avatarDimension)
                                        )
                                    } else {
                                        val avatarConfig = (playableArtifact?.avatarConfig?.takeIf { it != AvatarConfiguration.DEFAULT }
                                            ?: artifact?.avatarConfig
                                            ?: playableArtifact?.avatarConfig
                                            ?: AvatarConfiguration.DEFAULT).withSafeFallbacks()

                                        ArtifactAvatar(
                                            config = avatarConfig,
                                            size = avatarDimension,
                                            contentDescription = "Creator avatar",
                                            modifier = Modifier.clickable {
                                                onAuthorClick(uiState.internalOwnerId)
                                            }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(if (isCompactHeight) 6.dp else 10.dp))

                                // 2.2 Centered Artifact Title
                                val rawTitle = playableArtifact?.title ?: artifact?.title
                                val episodeNum = playableArtifact?.episodeNumber ?: artifact?.episodeNumber
                                val title = ArtifactTitleFormatter.formatTitle(rawTitle, episodeNum, fallbackTitle = "Artifact")

                                val titleStyle = if (isCompactHeight) {
                                    MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = (-0.3).sp
                                    )
                                } else {
                                    MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = (-0.4).sp
                                    )
                                }

                                Text(
                                    text = title,
                                    style = titleStyle,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp)
                                )

                                Spacer(modifier = Modifier.height(if (isCompactHeight) 2.dp else 4.dp))

                                // 2.3 Centered Anonymous Creator Name
                                if (isVerifiedDraft) {
                                    Text(
                                        text = "Private Draft",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFFFFB74D).copy(alpha = 0.7f),
                                        fontWeight = FontWeight.Light,
                                        letterSpacing = 1.sp,
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    val authorName = playableArtifact?.authorName?.ifEmpty { null }
                                        ?: artifact?.author?.name?.ifEmpty { null }
                                        ?: ResolvedCreatorIdentity.NEUTRAL_NAME

                                    Text(
                                        text = authorName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.clickable { onAuthorClick(uiState.internalOwnerId) }
                                    )
                                }

                                // 2.4 Centered Emotion Tags
                                val emotionsToDisplay = playableArtifact?.effectiveEmotions?.ifEmpty {
                                    listOfNotNull(playableArtifact.emotion.ifBlank { null })
                                } ?: artifact?.effectiveEmotions ?: emptyList()
                                if (emotionsToDisplay.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(if (isCompactHeight) 4.dp else 6.dp))
                                    EmotionTagsGroup(
                                        emotions = emotionsToDisplay,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            // 2.5 Relocated Following Control (UIUX-CHANGE-01)
                            if (!isVerifiedDraft && !uiState.isOwner) {
                                CreatorFollowButton(
                                    isFollowing = uiState.isResonating,
                                    syncStatus = uiState.followSyncStatus,
                                    onClick = onResonateConnectionClick,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 2.dp)
                                )
                            }
                        }
                    }

                    // 3. Waveform Scrubber & Timeline
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .zIndex(5f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val amplitudes = playableArtifact?.originalDraft?.amplitudeData
                            ?: artifact?.amplitudeData
                            ?: emptyList()

                        WaveformScrubber(
                            amplitudes = amplitudes,
                            progress = uiState.playbackProgress,
                            isPaused = !uiState.isPlaying,
                            onSeek = onSeek,
                            onScrubbing = onScrubbing,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val config = androidx.compose.ui.platform.LocalConfiguration.current

                            Text(
                                text = com.saurabh.artifact.util.TimeUtils.formatDurationMillis(uiState.currentPosition, config),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp
                            )

                            Text(
                                text = com.saurabh.artifact.util.TimeUtils.formatDurationMillis(uiState.durationMs, config),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp
                            )
                        }

                        if (!isVerifiedDraft && !uiState.isOwner) {
                            Spacer(modifier = Modifier.height(6.dp))
                            ListeningCoverageIndicator(
                                coveragePercent = uiState.coveragePercent,
                                isThresholdMet = uiState.isThresholdMet,
                                isPlaybackEnded = uiState.isPlaybackEnded,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // 4. Playback Controls & Speed Selector (Primary Playback Control Area)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PlaybackControls(
                            isPlaying = uiState.isPlaying,
                            onTogglePlay = onTogglePlayback,
                            onRewind = onRewind,
                            onForward = onForward
                        )

                        Spacer(modifier = Modifier.height(if (isCompactHeight) 2.dp else 4.dp))

                        PlaybackSpeedSelector(
                            currentSpeed = uiState.playbackSpeed,
                            onSpeedSelected = onSpeedChange
                        )
                    }

                    // 5. Interaction Layer (Standardized 4-item toolbar or Draft Actions)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = if (isCompactHeight) 6.dp else 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isVerifiedDraft) {
                            ReviewInteractionLayer(
                                state = reviewInteractionState,
                                onEditClick = onEditClick,
                                onPublishClick = onPublishClick,
                                onDeleteClick = { showDeleteConfirm = true }
                            )
                        } else {
                            PlayerInteractionBar(
                                isResonated = uiState.isResonated,
                                resonanceSyncStatus = uiState.resonanceSyncStatus,
                                selectedReactionType = uiState.selectedReactionType,
                                onResonateClick = onResonateClick,
                                resonanceCount = uiState.resonanceCount,
                                canShowResonators = uiState.canShowResonators,
                                onResonatorsCountClick = {
                                    artifact?.id?.let { id -> onResonatorsCountClick(id) }
                                },
                                isSaved = uiState.isSaved,
                                saveSyncStatus = uiState.saveSyncStatus,
                                onSaveClick = onSaveClick,
                                onCommentClick = onCommentClick,
                                isCommentEnabled = uiState.isThresholdMet,
                                commentDisabledReason = if (!uiState.isThresholdMet) "Listen to at least 95% of this Artifact before joining the conversation." else null,
                                isOwner = uiState.isOwner,
                                showSave = true,
                                qualifiedPlayCount = uiState.qualifiedPlayCount,
                                showQualifiedPlayCount = !isVerifiedDraft && artifact != null && uiState.loadState != PlayerLoadState.LOADING,
                                commentCount = uiState.commentCount,
                                savedCount = uiState.savedCount
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Isolated Header component for the player.
 * Extracted to prevent recomposition when playback state changes.
 */
@Composable
private fun PlayerHeader(
    artifact: PlayerArtifact?,
    showTranscript: Boolean,
    isVerifiedDraft: Boolean,
    onCollapse: () -> Unit,
    onToggleTranscript: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasTranscript = artifact?.transcript?.isNotEmpty() == true

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .zIndex(10f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onCollapse,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                Icons.Rounded.KeyboardArrowDown, 
                contentDescription = "Collapse", 
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(32.dp)
            )
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = if (showTranscript) "Transcript" else "Now Playing",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                letterSpacing = 1.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasTranscript) {
                IconButton(
                    onClick = onToggleTranscript,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (showTranscript) Icons.Rounded.Audiotrack else Icons.Rounded.Description,
                        contentDescription = "Toggle Transcript",
                        tint = if (showTranscript) GoldAura400 else Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            if (!isVerifiedDraft) {
                IconButton(
                    onClick = onMoreClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "More Options",
                        tint = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

/**
 * Separate Listening Coverage Indicator.
 * Displays cumulative listening coverage and policy requirements without conflating playback position.
 */
@Composable
fun ListeningCoverageIndicator(
    coveragePercent: Float,
    isThresholdMet: Boolean,
    isPlaybackEnded: Boolean,
    modifier: Modifier = Modifier,
    isCommentUnlocked: Boolean = false,
    isVerificationPending: Boolean = false
) {
    if (isCommentUnlocked) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Conversation unlocked"
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = GoldAura400,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Conversation unlocked",
                style = MaterialTheme.typography.labelSmall,
                color = GoldAura400,
                fontSize = 11.sp,
                letterSpacing = 0.2.sp
            )
        }
    } else if (isThresholdMet) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (isVerificationPending) {
                        "Listening requirement completed. Your listening evidence is being verified."
                    } else {
                        "Listening requirement completed"
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = GoldAura400,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Listening requirement completed",
                    style = MaterialTheme.typography.labelSmall,
                    color = GoldAura400,
                    fontSize = 11.sp,
                    letterSpacing = 0.2.sp
                )
            }
            if (isVerificationPending) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Your listening evidence is being verified.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    letterSpacing = 0.2.sp
                )
            }
        }
    } else {
        val clampedCoverage = coveragePercent.coerceIn(0f, 1f)
        val percentageInt = (clampedCoverage * 100).toInt()
        val endConditionText = if (isPlaybackEnded) "end reached" else "full listen required"
        val statusText = "Listening: $percentageInt% • 95% & $endConditionText to comment"
        val accessibilityEndText = if (isPlaybackEnded) "end reached" else "full listen required"
        val accessibilityText = "Listening coverage $percentageInt percent, 95 percent and $accessibilityEndText to comment"

        Column(
            modifier = modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = clampedCoverage,
                        range = 0f..1f,
                        steps = 0
                    )
                    contentDescription = accessibilityText
                    stateDescription = "$percentageInt%"
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LinearProgressIndicator(
                progress = { clampedCoverage },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = GoldAura400.copy(alpha = 0.6f),
                trackColor = Color.White.copy(alpha = 0.08f),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                letterSpacing = 0.2.sp
            )
        }
    }
}

/**
 * Restrained, accessible presentation of authoritative qualified play count.
 * Displays only in Extended Player. Formats count safely and includes semantic description.
 */
@Composable
fun QualifiedPlayCountDisplay(
    count: Long,
    modifier: Modifier = Modifier
) {
    val formattedCount = remember(count) {
        java.text.NumberFormat.getIntegerInstance().format(count)
    }
    val label = if (count == 1L) "qualified play" else "qualified plays"
    val fullText = "$formattedCount $label"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = fullText
            }
    ) {
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = fullText,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
            letterSpacing = 0.2.sp
        )
    }
}

@Preview
@Composable
fun ImmersiveDraftPlayerPreview() {
    MaterialTheme {
        ImmersivePlayerScreen(
            artifact = PlayerArtifact(
                id = "test_id",
                title = "Midnight Reflections",
                author = AuthorSnapshot(name = "Silent Wanderer", sigil = "A1"),
                emotion = "Peaceful",
                audioUrl = "",
                durationMs = 60000,
                amplitudeData = List(100) { (it % 10) / 10f },
                createdAt = com.google.firebase.Timestamp.now(),
                transcript = emptyList(),
                isPublic = false,
                isDraft = true
            ),
            uiState = PlayerUiState(
                isPlaying = false,
                listeningProgress = 0.4f,
                isResonated = false,
                isResonating = false,
                isSaved = true,
                isOwner = true
            ),
            reviewInteractionState = ReviewInteractionUiState(),
            onCollapse = {},
            onTogglePlayback = {},
            onRewind = {},
            onForward = {},
            onSpeedChange = {},
            onSeek = {},
            playableArtifact = null
        )
    }
}

@Preview
@Composable
fun ImmersivePublishedPlayerPreview() {
    MaterialTheme {
        ImmersivePlayerScreen(
            artifact = PlayerArtifact(
                id = "test_pub_1",
                title = "Midnight Reflections",
                author = AuthorSnapshot(name = "Silent Wanderer", sigil = "A1"),
                emotion = "Peaceful",
                audioUrl = "",
                durationMs = 60000,
                amplitudeData = List(100) { (it % 10) / 10f },
                createdAt = com.google.firebase.Timestamp.now(),
                transcript = emptyList(),
                isPublic = true,
                isDraft = false
            ),
            uiState = PlayerUiState(
                isPlaying = false,
                listeningProgress = 0.4f,
                isResonated = true,
                resonanceCount = 12,
                isResonating = false,
                isSaved = false,
                isOwner = false,
                qualifiedPlayCount = 42L,
                commentCount = 5L,
                loadState = PlayerLoadState.LOADED
            ),
            reviewInteractionState = ReviewInteractionUiState(),
            onCollapse = {},
            onTogglePlayback = {},
            onRewind = {},
            onForward = {},
            onSpeedChange = {},
            onSeek = {},
            playableArtifact = null
        )
    }
}

@Preview
@Composable
fun ImmersiveFollowingPlayerPreview() {
    MaterialTheme {
        ImmersivePlayerScreen(
            artifact = PlayerArtifact(
                id = "test_pub_2",
                title = "Midnight Reflections",
                author = AuthorSnapshot(name = "Silent Wanderer", sigil = "A1"),
                emotion = "Peaceful",
                audioUrl = "",
                durationMs = 60000,
                amplitudeData = List(100) { (it % 10) / 10f },
                createdAt = com.google.firebase.Timestamp.now(),
                transcript = emptyList(),
                isPublic = true,
                isDraft = false
            ),
            uiState = PlayerUiState(
                isPlaying = false,
                listeningProgress = 0.4f,
                isResonated = true,
                resonanceCount = 12,
                isResonating = true,
                isSaved = true,
                isOwner = false,
                qualifiedPlayCount = 42L,
                commentCount = 5L,
                loadState = PlayerLoadState.LOADED
            ),
            reviewInteractionState = ReviewInteractionUiState(),
            onCollapse = {},
            onTogglePlayback = {},
            onRewind = {},
            onForward = {},
            onSpeedChange = {},
            onSeek = {},
            playableArtifact = null
        )
    }
}

