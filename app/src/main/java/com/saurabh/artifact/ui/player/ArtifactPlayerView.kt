package com.saurabh.artifact.ui.player

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.presentation.avatar.model.AvatarConfig
import com.saurabh.artifact.presentation.avatar.renderer.AvatarRenderer
import com.saurabh.artifact.ui.player.components.*
import com.saurabh.artifact.ui.theme.EmberGlow
import com.saurabh.artifact.ui.components.motion.MotionTokens
import kotlinx.serialization.json.Json

@Composable
fun ArtifactAvatar(
    emoji: String,
    avatarConfigJson: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (!avatarConfigJson.isNullOrBlank()) {
            val config = remember(avatarConfigJson) {
                try {
                    Json.decodeFromString<AvatarConfig>(avatarConfigJson)
                } catch (e: Exception) {
                    android.util.Log.e("ArtifactPlayerView", "Failed to decode avatar config", e)
                    null
                }
            }
            if (config != null) {
                AvatarRenderer(
                    config = config,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(text = emoji, fontSize = 24.sp)
            }
        } else {
            Text(text = emoji, fontSize = 24.sp)
        }
    }
}

@Composable
fun ArtifactPlayerView(
    isVisible: Boolean = true,
    onNavigateToDraftEdit: (String) -> Unit = {},
    onNavigateToPublish: (String) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    // Safety check: if nothing is playing and we're hidden, render nothing.
    if (uiState.currentArtifact == null && uiState.playerMode == PlayerMode.HIDDEN) return

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. MINI PLAYER
        AnimatedVisibility(
            visible = uiState.playerMode == PlayerMode.MINI && isVisible,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            MiniPlayer(
                uiState = uiState,
                onExpand = { viewModel.setExpanded(true) },
                onTogglePlay = { viewModel.togglePlayPause() }
            )
        }

        // 2. FULLSCREEN IMMERSIVE PLAYER
        AnimatedVisibility(
            visible = uiState.playerMode == PlayerMode.FULLSCREEN,
            enter = slideInVertically(
                initialOffsetY = { it }, 
                animationSpec = tween(MotionTokens.DURATION_LONG)
            ),
            exit = slideOutVertically(
                targetOffsetY = { it }, 
                animationSpec = tween(MotionTokens.DURATION_LONG)
            )
        ) {
            val artifact = uiState.currentArtifact ?: return@AnimatedVisibility
            ImmersivePlayerScreen(
                artifact = artifact,
                uiState = uiState,
                onCollapse = { viewModel.setExpanded(false) },
                onTogglePlayback = { viewModel.togglePlayPause() },
                onRewind = { viewModel.rewind() },
                onForward = { viewModel.forward() },
                onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                onSeek = { viewModel.seekTo((it * uiState.duration).toLong()) },
                onShowAdvanced = { viewModel.setShowAdvancedControls(true) },
                onCommentClick = { /* Navigate to comments */ },
                onEditClick = { 
                    viewModel.setExpanded(false)
                    onNavigateToDraftEdit(artifact.audioUrl) 
                },
                onPublishClick = {
                    viewModel.setExpanded(false)
                    onNavigateToPublish(artifact.id)
                },
                onDeleteClick = {
                    viewModel.deleteCurrentArtifact()
                }
            )
        }

        // 3. ADVANCED CONTROLS
        if (uiState.showAdvancedControls) {
            AdvancedControlsSheet(
                isSilenceSkipEnabled = uiState.isSilenceSkipEnabled,
                onSilenceSkipToggle = { viewModel.toggleSilenceSkipping() },
                sleepTimerMillisRemaining = uiState.sleepTimerMillisRemaining,
                onSleepTimerSelected = { viewModel.startSleepTimer(it) },
                currentSpeed = uiState.playbackSpeed,
                onSpeedSelected = { viewModel.setPlaybackSpeed(it) },
                onDismiss = { viewModel.setShowAdvancedControls(false) }
            )
        }
    }
}

@Composable
fun MiniPlayer(
    uiState: PlayerUiState,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit
) {
    val artifact = uiState.currentArtifact ?: return
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable { onExpand() },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtifactAvatar(
                emoji = artifact.userEmoji,
                avatarConfigJson = artifact.avatarConfigJson,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.05f))
            )
            
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = artifact.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                com.saurabh.artifact.ui.components.AmbientWaveform(
                    amplitudes = artifact.amplitudeData.takeIf { it.isNotEmpty() } ?: listOf(0.4f, 0.6f, 0.3f, 0.8f, 0.5f, 0.7f, 0.4f, 0.6f),
                    progress = uiState.listeningProgress,
                    modifier = Modifier.height(16.dp).fillMaxWidth(),
                    isPaused = !uiState.isPlaying,
                    context = com.saurabh.artifact.ui.components.WaveformContext.Mini
                )
            }
            
            IconButton(
                onClick = onTogglePlay,
                colors = IconButtonDefaults.iconButtonColors(contentColor = EmberGlow)
            ) {
                if (uiState.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = EmberGlow
                    )
                } else {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
