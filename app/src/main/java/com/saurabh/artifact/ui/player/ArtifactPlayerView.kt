package com.saurabh.artifact.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.ui.comments.CommentsScreen
import com.saurabh.artifact.ui.components.base.AppButton
import com.saurabh.artifact.ui.components.base.AppEmptyState
import com.saurabh.artifact.ui.components.motion.MotionTokens
import com.saurabh.artifact.ui.player.components.AdvancedControlsSheet
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.ui.theme.ZIndexTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactPlayerView(
    onNavigateToDraftEdit: (String) -> Unit = {},
    onNavigateToPublish: (String) -> Unit = {},
    onNavigateToComments: (String, String) -> Unit = { _, _ -> },
    onReportArtifact: (String) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    var showOptionsSheet by remember { mutableStateOf(false) }

    // Observe interaction errors from the ViewModel
    LaunchedEffect(Unit) {
        viewModel.interactionError.collect { errorMessage ->
            snackbarHostState.showSnackbar(
                message = errorMessage,
                duration = SnackbarDuration.Short
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.shareEvent.collect { payload ->
            val shareText = com.saurabh.artifact.util.ShareFormatter.formatShareText(payload)

            val sendIntent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
            }

            val shareIntent = android.content.Intent.createChooser(sendIntent, null)
            context.startActivity(shareIntent)
        }
    }
    
    // Safety check: if nothing is playing and we're hidden, render nothing.
    if ((uiState.currentArtifact == null) && (uiState.playerMode == PlayerMode.HIDDEN)) return

    // Handle back press to minimize player if expanded
    BackHandler(enabled = uiState.playerMode == PlayerMode.FULLSCREEN) {
        viewModel.setExpanded(false)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 0. LOADING & ERROR OVERLAYS
        if (uiState.loadState == PlayerLoadState.LOADING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Obsidian950)
                    .zIndex(ZIndexTokens.FULL_SCREEN_OVERLAYS + 2f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = GoldAura400)
            }
        }

        if (uiState.loadState == PlayerLoadState.ERROR) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Obsidian950)
                    .zIndex(ZIndexTokens.FULL_SCREEN_OVERLAYS + 2f),
                contentAlignment = Alignment.Center
            ) {
                AppEmptyState(
                    title = "Something went wrong",
                    description = uiState.error ?: "Failed to load artifact",
                    emoji = "🌑",
                    action = {
                        AppButton(
                            text = "Retry",
                            onClick = {
                                uiState.currentPlayableArtifact?.let { 
                                    viewModel.playArtifactById(it.id, it.sourceType)
                                }
                            }
                        )
                    }
                )
            }
        }

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
            val artifact = uiState.currentArtifact
            val playable = uiState.currentPlayableArtifact
            
            if (artifact != null || playable != null) {
                ImmersivePlayerScreen(
                    artifact = artifact,
                    playableArtifact = playable,
                    uiState = uiState,
                    onCollapse = { viewModel.setExpanded(expanded = false) },
                    onTogglePlayback = { viewModel.togglePlayPause() },
                    onRewind = { viewModel.rewind() },
                    onForward = { viewModel.forward() },
                    onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                    onSeek = { viewModel.seekTo((it * uiState.durationMs).toLong()) },
                    onScrubbing = { viewModel.onScrubbing((it * uiState.durationMs).toLong()) },
                    onShowAdvanced = { viewModel.setShowAdvancedControls(true) },
                    onCommentClick = { 
                        viewModel.setShowComments(true)
                    },
                    onResonateClick = { viewModel.toggleResonate(it) },
                    onResonateConnectionClick = { viewModel.toggleResonanceConnection() },
                    onSaveClick = { viewModel.toggleSave() },
                    onEditClick = { viewModel.onEditClick(onNavigateToDraftEdit) },
                    onPublishClick = { viewModel.onPublishClick(onNavigateToPublish) },
                    onDeleteClick = {
                        viewModel.deleteCurrentArtifact()
                    },
                    onMoreClick = { showOptionsSheet = true }
                )
            }
        }

        // 2. ARTIFACT OPTIONS (SHARE, REPORT, ETC)
        if (showOptionsSheet) {
            val artifact = uiState.currentArtifact
            if (artifact != null) {
                com.saurabh.artifact.ui.components.ArtifactOptionsSheet(
                    isOwner = uiState.isOwner,
                    isPublic = true, // Player artifacts are always public for now
                    isDraft = uiState.isOwner && artifact.isDraft, // Only owner's drafts are drafts in this context
                    onReportClick = {
                        onReportArtifact(artifact.id)
                        showOptionsSheet = false
                    },
                    onDismiss = { showOptionsSheet = false },
                    onDeleteClick = {
                        viewModel.deleteCurrentArtifact()
                        showOptionsSheet = false
                    },
                    onShareClick = {
                        viewModel.onShareClicked()
                    }
                )
            }
        }

        // 3. ADVANCED CONTROLS
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

        // 3. COMMENTS SHEET
        if (uiState.showComments) {
            val artifact = uiState.currentArtifact
            if (artifact != null) {
                ModalBottomSheet(
                    onDismissRequest = { viewModel.setShowComments(false) },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = ArtifactTheme.colors.surfaceHearth,
                    dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.2f)) }
                ) {
                    CommentsScreen(
                        artifactId = artifact.id,
                        ownerId = uiState.internalOwnerId,
                        onBack = { viewModel.setShowComments(false) }
                    )
                }
            }
        }

        // 4. Error Notifications
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
                .zIndex(ZIndexTokens.FULL_SCREEN_OVERLAYS + 1f)
        )
    }
}
