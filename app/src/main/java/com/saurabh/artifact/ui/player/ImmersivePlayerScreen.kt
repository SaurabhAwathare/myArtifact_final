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
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.ui.components.ResonanceDisplay
import com.saurabh.artifact.ui.player.components.*
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.theme.Obsidian950
import kotlin.math.roundToInt

/**
 * ImmersivePlayerScreen - The unified cinematic listening and draft review space.
 */
@Composable
fun ImmersivePlayerScreen(
    artifact: Artifact,
    uiState: PlayerUiState,
    onCollapse: () -> Unit,
    onTogglePlayback: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    onShowAdvanced: () -> Unit,
    onCommentClick: () -> Unit,
    onResonateClick: (com.saurabh.artifact.model.ReactionType) -> Unit = {},
    onResonateConnectionClick: () -> Unit = {},
    onSaveClick: () -> Unit = {},
    onEditClick: () -> Unit = {},
    onPublishClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {}
) {
    var showTranscript by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    // 0. System Back Handling
    BackHandler {
        onCollapse()
    }
    
    // Gesture State for swipe-down to collapse
    var offsetY by remember { mutableStateOf(0f) }

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
        EmotionalBackground(
            emotion = artifact.emotion,
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
            // 2. Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp)
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
                
                IconButton(
                    onClick = { showTranscript = !showTranscript },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (showTranscript) Icons.Rounded.Audiotrack else Icons.Rounded.Description,
                        contentDescription = "Toggle Transcript",
                        tint = if (showTranscript) GoldAura400 else Color.White.copy(alpha = 0.8f)
                    )
                }
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
                            segments = artifact.transcript,
                            currentPosition = uiState.currentPosition,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (artifact.isDraft) {
                                EmotionalAudioSurface(
                                    emotion = artifact.emotion,
                                    isPlaying = uiState.isPlaying,
                                    modifier = Modifier
                                        .size(280.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(280.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Keep a subtle emotional glow behind the avatar
                                    EmotionalAudioSurface(
                                        emotion = artifact.emotion,
                                        isPlaying = uiState.isPlaying,
                                        modifier = Modifier.fillMaxSize().alpha(0.3f)
                                    )
                                    
                                    com.saurabh.artifact.ui.components.ArtifactAvatar(
                                        config = artifact.authorAvatarConfig,
                                        size = 180.dp
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(32.dp))
                            
                            Text(
                                text = artifact.title,
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            if (artifact.isDraft) {
                                Text(
                                    text = "Private Draft",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color(0xFFFFB74D).copy(alpha = 0.8f),
                                    fontWeight = FontWeight.Light
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = artifact.author.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = Color.White.copy(alpha = 0.5f),
                                            fontWeight = FontWeight.Medium
                                        )
                                        if (artifact.author.sigil.isNotEmpty()) {
                                            Text(
                                                text = " · ",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = Color.White.copy(alpha = 0.2f)
                                            )
                                            Text(
                                                text = artifact.author.sigil,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color.White.copy(alpha = 0.4f),
                                                fontWeight = FontWeight.Light
                                            )
                                        }
                                    }
                                    
                                    // Unified Metadata Display (Matches Feed)
                                    ResonanceDisplay(
                                        summary = uiState.resonanceSummary,
                                        isOwner = artifact.userId == uiState.currentArtifact?.userId
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Waveform Scrubber
            Box(
                modifier = Modifier
                    .padding(vertical = 24.dp)
                    .zIndex(5f)
            ) {
                WaveformScrubber(
                    amplitudes = artifact.amplitudeData,
                    progress = uiState.playbackProgress,
                    isPaused = !uiState.isPlaying,
                    onSeek = onSeek,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 5. Playback Controls
            PlaybackControls(
                isPlaying = uiState.isPlaying,
                onTogglePlay = onTogglePlayback,
                onRewind = onRewind,
                onForward = onForward
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 6. Interaction Layer
            if (!artifact.isDraft) {
                    PlayerInteractionBar(
                        isResonated = uiState.isResonated,
                        selectedReactionType = uiState.selectedReactionType,
                        onResonateClick = onResonateClick,
                        isResonating = uiState.isResonating,
                        onResonateConnectionClick = onResonateConnectionClick,
                        isSaved = uiState.isSaved,
                        onSaveClick = onSaveClick,
                        isCommentUnlocked = uiState.isCommentUnlocked,
                        commentCount = uiState.commentCount,
                        onCommentClick = onCommentClick,
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

            // 7. Context Actions
            if (artifact.isDraft) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onEditClick) {
                        Text("Edit", color = Color.White.copy(alpha = 0.7f))
                    }
                    Button(
                        onClick = onPublishClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Publish", color = Color.White)
                    }
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text("Delete", color = Color(0xFFE91E63).copy(alpha = 0.7f))
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .clickable(enabled = uiState.isCommentUnlocked, onClick = onCommentClick)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.EditNote,
                        contentDescription = null,
                        tint = if (uiState.isCommentUnlocked) GoldAura400 else Color.White.copy(alpha = 0.2f),
                        modifier = Modifier.size(24.dp).padding(end = 12.dp)
                    )

                    val message = if (uiState.isCommentUnlocked) {
                        "Reflect and respond"
                    } else {
                        "Listen to unlock thoughts..."
                    }
                    
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.isCommentUnlocked) Color.White else Color.White.copy(alpha = 0.3f),
                        fontWeight = if (uiState.isCommentUnlocked) FontWeight.Medium else FontWeight.Normal,
                        letterSpacing = 0.3.sp
                    )
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    IconButton(
                        onClick = onShowAdvanced,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Rounded.MoreVert, 
                            contentDescription = "More Options", 
                            tint = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
            artifact = Artifact(
                title = "Midnight Reflections",
                author = AuthorSnapshot(name = "Silent Wanderer", sigil = "A1"),
                emotion = "Peaceful",
                amplitudeData = List(100) { (it % 10) / 10f },
                isDraft = true
            ),
            uiState = PlayerUiState(
                isPlaying = false,
                listeningProgress = 0.4f,
                isResonated = false,
                isResonating = false,
                isSaved = true
            ),
            onCollapse = {},
            onTogglePlayback = {},
            onRewind = {},
            onForward = {},
            onSpeedChange = {},
            onSeek = {},
            onShowAdvanced = {},
            onCommentClick = {}
        )
    }
}
