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
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.ui.player.components.*
import com.saurabh.artifact.ui.theme.EmberGlow
import com.saurabh.artifact.ui.components.motion.MotionTokens
import com.saurabh.artifact.ui.components.ArtifactAvatar
import com.saurabh.artifact.ui.theme.ZIndexTokens

@Composable
fun ArtifactPlayerView(
    onNavigateToDraftEdit: (String) -> Unit = {},
    onNavigateToPublish: (String) -> Unit = {},
    onNavigateToComments: (String, String) -> Unit = { _, _ -> },
    onReportArtifact: (String) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    // Safety check: if nothing is playing and we're hidden, render nothing.
    if ((uiState.currentArtifact == null) && (uiState.playerMode == PlayerMode.HIDDEN)) return

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. FULLSCREEN IMMERSIVE PLAYER
        AnimatedVisibility(
            visible = uiState.playerMode == PlayerMode.FULLSCREEN,
            enter = slideInVertically(
                initialOffsetY = { it }, 
                animationSpec = tween(MotionTokens.DURATION_LONG)
            ),
            exit = slideOutVertically(
                targetOffsetY = { it }, 
                animationSpec = tween(MotionTokens.DURATION_LONG)
            ),
            modifier = Modifier.zIndex(ZIndexTokens.FULL_SCREEN_OVERLAYS)
        ) {
            val artifact = uiState.currentArtifact ?: return@AnimatedVisibility
            ImmersivePlayerScreen(
                artifact = artifact,
                uiState = uiState,
                onCollapse = { viewModel.setExpanded(expanded = false) },
                onTogglePlayback = { viewModel.togglePlayPause() },
                onRewind = { viewModel.rewind() },
                onForward = { viewModel.forward() },
                onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                onSeek = { viewModel.seekTo((it * uiState.durationMs).toLong()) },
                onShowAdvanced = { viewModel.setShowAdvancedControls(true) },
                onCommentClick = { 
                    uiState.currentArtifact?.let { art ->
                        viewModel.setExpanded(false)
                        onNavigateToComments(art.id, art.userId)
                    }
                },
                onResonateClick = { viewModel.toggleResonate(it) },
                onResonateConnectionClick = { viewModel.toggleResonanceConnection() },
                onSaveClick = { viewModel.toggleSave() },
                onEditClick = { 
                    viewModel.setExpanded(false)
                    onNavigateToDraftEdit(artifact.id)
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

        // 2. ADVANCED CONTROLS
        if (uiState.showAdvancedControls) {
            Box(modifier = Modifier.zIndex(ZIndexTokens.MODAL_OVERLAYS)) {
                AdvancedControlsSheet(
                    isSilenceSkipEnabled = uiState.isSilenceSkipEnabled,
                    onSilenceSkipToggle = { viewModel.toggleSilenceSkipping() },
                    sleepTimerMillisRemaining = uiState.sleepTimerMillisRemaining,
                    onSleepTimerSelected = { viewModel.startSleepTimer(it) },
                    currentSpeed = uiState.playbackSpeed,
                    onSpeedSelected = { viewModel.setPlaybackSpeed(it) },
                    onReportClick = {
                        uiState.currentArtifact?.let { onReportArtifact(it.id) }
                        viewModel.setShowAdvancedControls(false)
                    },
                    onDismiss = { viewModel.setShowAdvancedControls(false) }
                )
            }
        }
    }
}
