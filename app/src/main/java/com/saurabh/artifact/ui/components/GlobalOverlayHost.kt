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
import androidx.navigation.compose.currentBackStackEntryAsState
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.audio.PublishStateManager
import com.saurabh.artifact.navigation.Screen
import com.saurabh.artifact.ui.theme.ZIndexTokens
import com.saurabh.artifact.ui.player.ArtifactPlayerView
import com.saurabh.artifact.ui.player.PlayerViewModel
import com.saurabh.artifact.ui.player.PlayerMode
import com.saurabh.artifact.ui.player.components.MiniPlayer
import com.saurabh.artifact.ui.recording.components.MiniRecorder

@Composable
fun GlobalOverlayHost(
    navController: NavController,
    recordingSessionManager: RecordingSessionManager,
    publishStateManager: PublishStateManager,
    onNavigateToDraftEdit: (String) -> Unit,
    onNavigateToPublish: (String) -> Unit,
    onNavigateToComments: (String, String) -> Unit,
    onReportArtifact: (String) -> Unit,
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val showOverlays = isOverlayVisibleOnRoute(currentRoute)
    
    val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val recordingState by recordingSessionManager.sessionState.collectAsStateWithLifecycle()
    val publishState by publishStateManager.currentPublishState.collectAsStateWithLifecycle()

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
                        navController.navigate(Screen.InstantRecord.route) {
                            launchSingleTop = true
                        }
                    }
                )

                // Upload Bar
                AmbientUploadBar(
                    state = publishState,
                    onDismiss = { publishStateManager.dismissSession() }
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

fun isOverlayVisibleOnRoute(route: String?): Boolean {
    if (route == null) return false
    val screensWithoutOverlays = listOf(
        Screen.InstantRecord.route,
        Screen.PreRecordingWarning.route,
        Screen.RecordingReview.route,
        Screen.PublishPreparation.route,
        Screen.PublishApproval.route,
        Screen.IdentitySelection.route,
        Screen.PresenceBuilder.route,
        Screen.PostRecordingDecision.route
    )
    return route !in screensWithoutOverlays
}
