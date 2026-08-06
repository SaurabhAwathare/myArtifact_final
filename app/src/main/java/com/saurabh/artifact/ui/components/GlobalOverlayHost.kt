package com.saurabh.artifact.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.navigation.*
import com.saurabh.artifact.ui.theme.ZIndexTokens
import com.saurabh.artifact.ui.player.ArtifactPlayerView
import com.saurabh.artifact.ui.player.PlayerViewModel
import com.saurabh.artifact.ui.player.PlayerMode
import com.saurabh.artifact.ui.player.components.MiniPlayer
import com.saurabh.artifact.ui.recording.components.MiniRecorder
import com.saurabh.artifact.ui.util.BottomOverlayConstants
import com.saurabh.artifact.ui.util.LocalBottomOverlayOffset
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.startup.StartupStage

private val ScreensWithoutOverlays = listOf(
    InstantRecord::class,
    PreRecordingWarning::class,
    PublishPreparation::class,
    PublishApproval::class,
    IdentitySelection::class,
    PostRecordingDecision::class,
    PublishingStudio::class,
    DraftEdit::class,
    DraftList::class
)

@Composable
fun GlobalOverlayHost(
    navController: NavController,
    recordingSessionManager: RecordingSessionManager,
    onNavigateToDraftEdit: (String) -> Unit = { id -> 
        navController.navigate(PublishingStudio(id)) { launchSingleTop = true } 
    },
    onNavigateToPublish: (String) -> Unit = { id -> 
        navController.navigate(PublishingStudio(id)) { launchSingleTop = true } 
    },
    onReportArtifact: (String) -> Unit,
    onResonatorsCountClick: (String) -> Unit = { artifactId ->
        val isOwner = playerViewModel.uiState.value.isOwner
        navController.navigate(ResonanceList(artifactId = artifactId, isOwner = isOwner, title = "Resonators"))
    },
    playerViewModel: PlayerViewModel = hiltViewModel(),
    stage: StartupStage = StartupStage.STABLE,
    content: @Composable () -> Unit
) {
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val showOverlays = isOverlayVisibleOnRoute(currentDestination)
    
    val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val recordingState by recordingSessionManager.sessionState.collectAsStateWithLifecycle()

    val totalOffset = remember(showOverlays, uiState.playerMode, recordingState.status) {
        if (!showOverlays || uiState.playerMode == PlayerMode.FULLSCREEN) {
            0.dp
        } else {
            val isPlayerVisible = uiState.playerMode == PlayerMode.MINI
            val isRecorderVisible = recordingState.status == RecordingStatus.RECORDING || 
                                   recordingState.status == RecordingStatus.PAUSED
            
            var offset = 0.dp
            if (isPlayerVisible) offset += BottomOverlayConstants.MINI_PLAYER_HEIGHT
            if (isRecorderVisible) offset += BottomOverlayConstants.MINI_RECORDER_OCCUPIED_HEIGHT
            if (isPlayerVisible && isRecorderVisible) offset += BottomOverlayConstants.OVERLAY_SPACING
            offset
        }
    }

    // Phase 12: Navigation Guard - Prevent circular loop during Studio transition
    // Tracks the most recent draft navigation to bridge the gap while NavController updates its state asynchronously.
    var pendingStudioDraftId by remember { mutableStateOf<String?>(null) }

    // Sync guard with current destination to allow re-entry if the user explicitly leaves and returns
    LaunchedEffect(currentDestination) {
        if (currentDestination?.hasRoute(PublishingStudio::class) == false) {
            pendingStudioDraftId = null
        }
    }

    // Observe Navigation Events for Review Completion
    LaunchedEffect(Unit) {
        playerViewModel.navigateToPublish.collect { draftId ->
            val isAlreadyInStudio = navController.currentBackStackEntry?.destination?.hasRoute(PublishingStudio::class) == true
            
            // Allow navigation only if we aren't already there AND aren't currently transitioning there for this specific draft
            if (!isAlreadyInStudio && pendingStudioDraftId != draftId) {
                pendingStudioDraftId = draftId
                onNavigateToPublish(draftId)
            }
        }
    }

    CompositionLocalProvider(LocalBottomOverlayOffset provides totalOffset) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 0. ACTUAL APP CONTENT
            content()

            // 1. PLAYER SYSTEM (Full Screen & Hidden layers)
            // Staggered appearance via Stage, but structural stability is preserved.
            if (stage >= StartupStage.RITUAL || uiState.playerMode == PlayerMode.FULLSCREEN) {
                ArtifactPlayerView(
                    onNavigateToDraftEdit = onNavigateToDraftEdit,
                    onNavigateToPublish = onNavigateToPublish,
                    onReportArtifact = onReportArtifact,
                    onAuthorClick = { userId ->
                        if (userId.isNotEmpty()) {
                            // Collapse the expanded player before navigation so the destination
                            // screen is immediately visible while keeping playback active.
                            playerViewModel.setExpanded(false)
                            navController.navigate(Profile(userId))
                        }
                    },
                    onResonatorsCountClick = { artifactId ->
                        playerViewModel.setExpanded(false)
                        onResonatorsCountClick(artifactId)
                    },
                    viewModel = playerViewModel
                )
            }

            // 2. BOTTOM STACK (Floating Overlays)
            if (stage >= StartupStage.RITUAL && showOverlays && (uiState.playerMode != PlayerMode.FULLSCREEN)) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .zIndex(ZIndexTokens.MINI_OVERLAYS),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Mini Recorder
                    MiniRecorder(
                        status = recordingState.status,
                        durationSeconds = recordingState.durationSeconds,
                        onClick = {
                            navController.navigate(InstantRecord()) {
                                launchSingleTop = true
                            }
                        }
                    )

                    // Mini Player
                    androidx.compose.animation.AnimatedVisibility(
                        visible = uiState.playerMode == PlayerMode.MINI,
                        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
                    ) {
                        MiniPlayer(
                            uiState = uiState,
                            onExpand = { playerViewModel.setExpanded(true) },
                            onTogglePlay = { playerViewModel.togglePlayPause() }
                        )
                    }
                }
            }
        }
    }
}

fun isOverlayVisibleOnRoute(destination: NavDestination?): Boolean {
    if (destination == null) return false
    return ScreensWithoutOverlays.none { destination.hasRoute(it) }
}
