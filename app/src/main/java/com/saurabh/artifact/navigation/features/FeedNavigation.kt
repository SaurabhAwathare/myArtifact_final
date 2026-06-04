package com.saurabh.artifact.navigation.features

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.navigation.Screen
import com.saurabh.artifact.ui.comments.CommentsScreen
import com.saurabh.artifact.ui.feed.FeedScreen
import com.saurabh.artifact.ui.notifications.NotificationScreen
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

fun NavGraphBuilder.feedNavigation(
    navController: NavHostController,
    recordingSessionManager: RecordingSessionManager,
    onReportArtifact: (String) -> Unit,
) {
    composable(Screen.Home.route) {
        val onNavigateToProfile = remember(navController) {
            {
                navController.navigate(Screen.Profile.createRoute())
            }
        }

        val onNavigateToNotifications = remember(navController) {
            {
                navController.navigate(Screen.Notifications.route)
            }
        }

        val onNavigateToComments = remember(navController) {
            { artifactId: String, ownerId: String ->
                navController.navigate(Screen.Comments.createRoute(artifactId, ownerId))
            }
        }

        FeedScreen(
            onNavigateToRecord = { prompt ->
                if (recordingSessionManager.isRecordingActive()) {
                    navController.navigate(Screen.InstantRecord.createRoute(prompt)) {
                        launchSingleTop = true
                    }
                } else {
                    navController.navigate(Screen.PreRecordingWarning.createRoute(prompt)) {
                        launchSingleTop = true
                    }
                }
            },
            onNavigateToProfile = onNavigateToProfile,
            onNavigateToNotifications = onNavigateToNotifications,
            onNavigateToComments = onNavigateToComments,
            onReportArtifact = onReportArtifact
        )
    }

    composable(Screen.Feed.route) {
        val onNavigateToProfile = remember(navController) {
            {
                navController.navigate(Screen.Profile.createRoute())
            }
        }

        val onNavigateToNotifications = remember(navController) {
            {
                navController.navigate(Screen.Notifications.route)
            }
        }

        val onNavigateToComments = remember(navController) {
            { artifactId: String, ownerId: String ->
                navController.navigate(Screen.Comments.createRoute(artifactId, ownerId))
            }
        }

        FeedScreen(
            onNavigateToRecord = { prompt ->
                if (recordingSessionManager.isRecordingActive()) {
                    navController.navigate(Screen.InstantRecord.createRoute(prompt)) {
                        launchSingleTop = true
                    }
                } else {
                    navController.navigate(Screen.PreRecordingWarning.createRoute(prompt)) {
                        launchSingleTop = true
                    }
                }
            },
            onNavigateToProfile = onNavigateToProfile,
            onNavigateToNotifications = onNavigateToNotifications,
            onNavigateToComments = onNavigateToComments,
            onReportArtifact = onReportArtifact
        )
    }

    composable(Screen.Notifications.route) {
        NotificationScreen(
            onBackClick = { navController.popBackStack() },
            onNotificationClick = {
                // Logic to navigate to a specific artifact if needed
            }
        )
    }

    composable(
        route = Screen.Comments.route,
        arguments = listOf(
            navArgument("artifactId") { type = NavType.StringType },
            navArgument("ownerId") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val artifactId = URLDecoder.decode(backStackEntry.arguments?.getString("artifactId") ?: "", StandardCharsets.UTF_8.toString())
        val ownerId = URLDecoder.decode(backStackEntry.arguments?.getString("ownerId") ?: "", StandardCharsets.UTF_8.toString())
        CommentsScreen(
            artifactId = artifactId,
            ownerId = ownerId,
            onBack = { navController.popBackStack() }
        )
    }
}
