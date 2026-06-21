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
    onNavigateToComments: (String, String) -> Unit,
    onReportArtifact: (String) -> Unit,
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val showOverlays = isOverlayVisibleOnRoute(currentDestination)
    
    val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val recordingState by recordingSessionManager.sessionState.collectAsStateWithLifecycle()

    // Observe Navigation Events for Review Completion
    LaunchedEffect(Unit) {
        playerViewModel.navigateToPublish.collect { draftId ->
            val isAlreadyInStudio = navController.currentBackStackEntry?.destination?.hasRoute(PublishingStudio::class) == true
            if (!isAlreadyInStudio) {
                onNavigateToPublish(draftId)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. PLAYER SYSTEM
        ArtifactPlayerView(
            onNavigateToDraftEdit = onNavigateToDraftEdit,
            onNavigateToPublish = onNavigateToPublish,
            onNavigateToComments = onNavigateToComments,
            onReportArtifact = onReportArtifact,
            viewModel = playerViewModel
        )

        // 2. BOTTOM STACK (Floating Overlays)
        if (showOverlays && (uiState.playerMode != PlayerMode.FULLSCREEN)) {
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

fun isOverlayVisibleOnRoute(destination: NavDestination?): Boolean {
    if (destination == null) return false
    return ScreensWithoutOverlays.none { destination.hasRoute(it) }
}
