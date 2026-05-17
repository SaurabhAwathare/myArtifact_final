package com.saurabh.artifact.ui.player

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.saurabh.artifact.model.Artifact
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
    onEditClick: () -> Unit = {},
    onPublishClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {}
) {
    var showTranscript by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
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
    ) {
        // 1. Emotional Background Engine
        EmotionalBackground(
            emotion = artifact.emotion,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            offsetY = (offsetY + dragAmount.y).coerceAtLeast(0f)
                        },
                        onDragEnd = {
                            if (offsetY > 300f) {
                                onCollapse()
                            }
                            offsetY = 0f
                        }
                    )
                }
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
                    .padding(vertical = 8.dp)
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
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(32.dp)
                    )
                }
                
                IconButton(
                    onClick = { showTranscript = !showTranscript },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (showTranscript) Icons.Rounded.GraphicEq else Icons.Rounded.Description,
                        contentDescription = "Toggle Transcript",
                        tint = if (showTranscript) GoldAura400 else Color.White.copy(alpha = 0.6f)
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
                            ArtifactAvatar(
                                emoji = artifact.userEmoji,
                                avatarConfigJson = artifact.avatarConfigJson,
                                modifier = Modifier
                                    .size(240.dp)
                                    .clip(RoundedCornerShape(56.dp))
                                    .background(Color.White.copy(alpha = 0.03f))
                            )
                            
                            Spacer(modifier = Modifier.height(40.dp))
                            
                            Text(
                                text = artifact.title,
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            if (artifact.isDraft) {
                                Text(
                                    text = "Private Draft",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color(0xFFFFB74D).copy(alpha = 0.8f),
                                    fontWeight = FontWeight.Light
                                )
                            } else {
                                Text(
                                    text = "from ${artifact.username}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White.copy(alpha = 0.4f)
                                )
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
                    progress = uiState.listeningProgress,
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

            Spacer(modifier = Modifier.height(24.dp))

            PlaybackSpeedSelector(
                currentSpeed = uiState.playbackSpeed,
                onSpeedSelected = onSpeedChange
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 6. Context Actions
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
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        text = if (uiState.isCommentUnlocked) "Ready to respond" else "Listen fully to respond...",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (uiState.isCommentUnlocked) GoldAura400 else Color.White.copy(alpha = 0.3f),
                        fontStyle = if (uiState.isCommentUnlocked) androidx.compose.ui.text.font.FontStyle.Normal else androidx.compose.ui.text.font.FontStyle.Italic
                    )
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    IconButton(
                        onClick = onShowAdvanced,
                        modifier = Modifier.size(48.dp)
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
                username = "Silent Wanderer",
                emotion = "Peaceful",
                amplitudeData = List(100) { (it % 10) / 10f },
                isDraft = true
            ),
            uiState = PlayerUiState(
                isPlaying = false,
                listeningProgress = 0.4f
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
