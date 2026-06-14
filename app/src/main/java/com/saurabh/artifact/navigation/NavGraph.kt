package com.saurabh.artifact.navigation

import android.util.Log
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.navigation.features.authNavigation
import com.saurabh.artifact.navigation.features.feedNavigation
import com.saurabh.artifact.navigation.features.profileNavigation
import com.saurabh.artifact.navigation.features.recordingNavigation
import com.saurabh.artifact.ui.components.motion.MotionTokens
import com.saurabh.artifact.ui.player.PlayerViewModel
import com.saurabh.artifact.util.OnboardingManager

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: Any,
    recordingSessionManager: RecordingSessionManager,
    onboardingManager: OnboardingManager,
    onReportArtifact: (String) -> Unit,
    onPlayArtifactById: (String) -> Unit,
    playerViewModel: PlayerViewModel,
    onDestinationChanged: (String?) -> Unit = {}
) {
    // Stability access via VisualTier to avoid top-level invalidation of the NavHost
    val isStable = com.saurabh.artifact.ui.theme.ArtifactTheme.isStable

    Log.d("NAV_DEBUG", "NavGraph rendering. Start destination = $startDestination")

    androidx.compose.runtime.LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            val route = entry.destination.route
            Log.d("NAV_DEBUG", "Current route = $route")
            onDestinationChanged(route)
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            val duration = if (isStable) MotionTokens.DURATION_MEDIUM else 0
            fadeIn(animationSpec = tween(duration)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(duration, easing = MotionTokens.DefaultEasing)
            )
        },
        exitTransition = {
            val duration = if (isStable) MotionTokens.DURATION_SHORT else 0
            fadeOut(animationSpec = tween(duration)) + slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(duration, easing = MotionTokens.DefaultEasing)
            )
        },
        popEnterTransition = {
            val duration = if (isStable) MotionTokens.DURATION_MEDIUM else 0
            fadeIn(animationSpec = tween(duration)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(duration, easing = MotionTokens.DefaultEasing)
            )
        },
        popExitTransition = {
            val duration = if (isStable) MotionTokens.DURATION_SHORT else 0
            fadeOut(animationSpec = tween(duration)) + slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(duration, easing = MotionTokens.DefaultEasing)
            )
        }
    ) {
        authNavigation(navController, onboardingManager)
        feedNavigation(navController, recordingSessionManager, onReportArtifact, onPlayArtifactById)
        profileNavigation(navController, playerViewModel)
        recordingNavigation(navController)
    }
}
