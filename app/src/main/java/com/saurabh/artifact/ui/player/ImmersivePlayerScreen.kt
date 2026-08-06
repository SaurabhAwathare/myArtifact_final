package com.saurabh.artifact.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.saurabh.artifact.model.PlayableArtifact
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.ui.components.ResonanceDisplay
import com.saurabh.artifact.ui.player.components.*
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.theme.Obsidian950
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
    onShowAdvanced: () -> Unit,
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

    // Phase 4: Harden Draft Detection (Unified Logic)
    val isVerifiedDraft = artifact?.isDraft == true

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Draft?", color = Color.White) },
            text = { Text("This will permanently remove this private reflection.", color = Color.White.copy(alpha = 0.7f)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteClick()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE91E63))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.5f))
                }
            },
            containerColor = Color(0xFF1A0808)
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
                        change.consume()
                        // Only allow downward drag
                        offsetY = (offsetY + dragAmount).coerceAtLeast(0f)
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 2. Header (Phase 1 Refinement: Extracted for isolation)
            PlayerHeader(
                artifact = artifact,
                showTranscript = showTranscript,
                isVerifiedDraft = isVerifiedDraft,
                onCollapse = onCollapse,
                onToggleTranscript = { showTranscript = !showTranscript },
                onMoreClick = onMoreClick,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )

            // 2.5 Moderation Transparency (Creator Only)
            if (uiState.isOwner) {
                ModerationBanner(
                    recommendationState = uiState.recommendationState,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Presence Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = showTranscript,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(500))
                    },
                    label = "TranscriptTransition"
                ) { isShowingTranscript ->
                    if (isShowingTranscript) {
                        TranscriptOverlay(
                            segments = artifact?.transcript ?: emptyList(),
                            currentPosition = uiState.currentPosition,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            val title = playableArtifact?.title ?: artifact?.title ?: "Untitled"
                            val emotion = playableArtifact?.emotion ?: artifact?.emotion ?: ""
                            
                            if (isVerifiedDraft) {
                                EmotionalAudioSurface(
                                    emotion = emotion,
                                    isPlaying = uiState.isPlaying,
                                    modifier = Modifier
                                        .size(280.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(280.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Keep a subtle emotional glow behind the sigil
                                    EmotionalAudioSurface(
                                        emotion = emotion,
                                        isPlaying = uiState.isPlaying,
                                        modifier = Modifier.fillMaxSize().alpha(0.3f)
                                    )
                                    
                                    val sigilConfig = artifact?.author?.sigilConfig 
                                        ?: com.saurabh.artifact.model.SigilConfig(seed = playableArtifact?.sigilSeed ?: "")
                                        
                                    // Use Player-safe sigil seed if author.sigilSeed is empty
                                    val safeSigilConfig = sigilConfig.copy(
                                        seed = sigilConfig.seed.ifEmpty { 
                                            playableArtifact?.sigilSeed?.ifEmpty { artifact?.id } ?: artifact?.id ?: "" 
                                        }
                                    )

                                    com.saurabh.artifact.ui.components.ArtifactSigil(
                                        config = safeSigilConfig,
                                        size = 180.dp,
                                        modifier = Modifier.clickable { 
                                            android.util.Log.d("ImmersivePlayerScreen", "PLAYER_AUTHOR_CLICK: sigil click, userId=${uiState.internalOwnerId}")
                                            if (uiState.internalOwnerId.isEmpty()) {
                                                android.util.Log.e("ImmersivePlayerScreen", "PLAYER_AUTHOR_ID_EMPTY: sigil click")
                                            }
                                            onAuthorClick(uiState.internalOwnerId) 
                                        }
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(32.dp))
                            
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            if (isVerifiedDraft) {
                                Text(
                                    text = "Private Draft",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color(0xFFFFB74D).copy(alpha = 0.8f),
                                    fontWeight = FontWeight.Light
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val authorName = playableArtifact?.authorName ?: artifact?.author?.name ?: ""
                                        val authorSigil = playableArtifact?.authorSigil ?: artifact?.author?.sigil ?: ""
                                        
                                        Text(
                                            text = authorName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = Color.White.copy(alpha = 0.5f),
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.clickable { 
                                                android.util.Log.d("ImmersivePlayerScreen", "PLAYER_AUTHOR_CLICK: name click, userId=${uiState.internalOwnerId}")
                                                if (uiState.internalOwnerId.isEmpty()) {
                                                    android.util.Log.e("ImmersivePlayerScreen", "PLAYER_AUTHOR_ID_EMPTY: name click")
                                                }
                                                onAuthorClick(uiState.internalOwnerId) 
                                            }
                                        )
                                        if (authorSigil.isNotEmpty()) {
                                            Text(
                                                text = " · ",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = Color.White.copy(alpha = 0.2f)
                                            )
                                            Text(
                                                text = authorSigil,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color.White.copy(alpha = 0.4f),
                                                fontWeight = FontWeight.Light
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3.5 Social Resonance Entry Point (Discovery Gateway)
            if (!isVerifiedDraft && uiState.canShowResonators && uiState.resonanceCount > 0) {
                ResonanceDisplay(
                    summary = uiState.resonanceSummary,
                    isOwner = uiState.isOwner,
                    onClick = { 
                        artifact?.id?.let { id -> onResonatorsCountClick(id) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // 4. Waveform Scrubber & Timeline
            Column(
                modifier = Modifier
                    .padding(vertical = 24.dp)
                    .zIndex(5f)
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

                Spacer(modifier = Modifier.height(8.dp))
                
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
            }

            // 5. Playback Controls
            PlaybackControls(
                isPlaying = uiState.isPlaying,
                onTogglePlay = onTogglePlayback,
                onRewind = onRewind,
                onForward = onForward
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 6. Interaction Layer (Phase 5: Protect Draft Actions)
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
                    isResonating = uiState.isResonating,
                    followSyncStatus = uiState.followSyncStatus,
                    onResonateConnectionClick = onResonateConnectionClick,
                    isSaved = uiState.isSaved,
                    saveSyncStatus = uiState.saveSyncStatus,
                    onSaveClick = onSaveClick,
                    onCommentClick = onCommentClick,
                    isCommentEnabled = uiState.isThresholdMet,
                    commentDisabledReason = if (!uiState.isThresholdMet) "Listen to at least 95% of this Artifact before joining the conversation." else null,
                    showResonance = !uiState.isOwner,
                    showSave = !uiState.isOwner
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            PlaybackSpeedSelector(
                currentSpeed = uiState.playbackSpeed,
                onSpeedSelected = onSpeedChange
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Ensure padding even if no context action bar
            Spacer(modifier = Modifier.height(24.dp))
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
        
        Text(
            text = if (showTranscript) "Transcript" else "Now Playing",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.4f),
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

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
            onShowAdvanced = {},
            playableArtifact = null
        )
    }
}
