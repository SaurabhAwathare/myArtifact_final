package com.saurabh.artifact.navigation.features

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.navigation.*
import com.saurabh.artifact.ui.feed.FeedScreen
import com.saurabh.artifact.ui.notifications.NotificationScreen

fun NavGraphBuilder.feedNavigation(
    navController: NavHostController,
    recordingSessionManager: RecordingSessionManager,
    onReportArtifact: (String) -> Unit,
    onPlayArtifactById: (String) -> Unit,
    diagnosticLogger: DiagnosticLogger
) {
    val onNavigateToDebugMenu = {
        navController.navigate(DebugMenu)
    }

    composable<Home> {
        diagnosticLogger.debug(DiagnosticCategory.NAVIGATION, "NAVIGATE_TO_FEED", mapOf("source" to "Home"))
        val onNavigateToProfile = remember(navController) {
            {
                navController.navigate(Profile())
            }
        }

        val onNavigateToNotifications = remember(navController) {
            {
                navController.navigate(Notifications)
            }
        }

        FeedScreen(
            onNavigateToRecord = { prompt ->
                if (recordingSessionManager.isRecordingActive()) {
                    navController.navigate(InstantRecord(prompt)) {
                        launchSingleTop = true
                    }
                } else {
                    navController.navigate(PreRecordingWarning(prompt)) {
                        launchSingleTop = true
                    }
                }
            },
            onNavigateToProfile = onNavigateToProfile,
            onNavigateToNotifications = onNavigateToNotifications,
            onNavigateToDebugMenu = onNavigateToDebugMenu,
            onReportArtifact = onReportArtifact
        )
    }

    composable<Feed> {
        diagnosticLogger.debug(DiagnosticCategory.NAVIGATION, "NAVIGATE_TO_FEED", mapOf("source" to "Feed"))
        val onNavigateToProfile = remember(navController) {
            {
                navController.navigate(Profile())
            }
        }

        val onNavigateToNotifications = remember(navController) {
            {
                navController.navigate(Notifications)
            }
        }

        FeedScreen(
            onNavigateToRecord = { prompt ->
                if (recordingSessionManager.isRecordingActive()) {
                    navController.navigate(InstantRecord(prompt)) {
                        launchSingleTop = true
                    }
                } else {
                    navController.navigate(PreRecordingWarning(prompt)) {
                        launchSingleTop = true
                    }
                }
            },
            onNavigateToProfile = onNavigateToProfile,
            onNavigateToNotifications = onNavigateToNotifications,
            onNavigateToDebugMenu = onNavigateToDebugMenu,
            onReportArtifact = onReportArtifact
        )
    }

    composable<Notifications> {
        NotificationScreen(
            onBackClick = { navController.popBackStack() },
            onNotificationClick = { artifactId ->
                onPlayArtifactById(artifactId)
                navController.popBackStack() // Return to feed to see the player
            }
        )
    }
}
