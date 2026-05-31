package com.saurabh.artifact.navigation

import android.util.Log
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.saurabh.artifact.ui.components.motion.MotionTokens
import com.saurabh.artifact.ui.feed.FeedScreen
import com.saurabh.artifact.ui.login.LoginScreen
import com.saurabh.artifact.ui.notifications.NotificationScreen
import com.saurabh.artifact.ui.profile.ProfileScreen
import com.saurabh.artifact.ui.settings.SettingsScreen
import com.saurabh.artifact.ui.drafts.list.DraftListScreen
import com.saurabh.artifact.ui.recording.RecordingScreen
import com.saurabh.artifact.ui.recording.PostRecordingDecisionScreen
import com.saurabh.artifact.ui.identity.IdentitySelectionScreen
import com.saurabh.artifact.ui.player.ReviewPlayerScreen
import com.saurabh.artifact.ui.publish.PublishPreparationScreen
import com.saurabh.artifact.ui.publish.PublishApprovalScreen
import com.saurabh.artifact.ui.avatar.AvatarEditorScreen
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.startup.StartupStage
import com.saurabh.artifact.ui.theme.LocalStartupStage
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch

import com.saurabh.artifact.ui.onboarding.OnboardingScreen
import com.saurabh.artifact.util.OnboardingManager
import com.saurabh.artifact.ui.comments.CommentsScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String,
    recordingSessionManager: RecordingSessionManager,
    onboardingManager: OnboardingManager
) {
    val scope = rememberCoroutineScope()
    // Stability access via VisualTier to avoid top-level invalidation of the NavHost
    val isStable = com.saurabh.artifact.ui.theme.ArtifactTheme.isStable

    Log.d("NAV_DEBUG", "NavGraph rendering. Start destination = $startDestination")

    androidx.compose.runtime.LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect {
            Log.d("NAV_DEBUG", "Current route = ${it.destination.route}")
        }
    }

    rememberCoroutineScope()

    val onLoginSuccess = remember(navController) {
        {
            Log.d("APP_FLOW", "Action: Login Success -> Navigating Home")
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        }
    }

    val onNavigateToProfile = remember(navController) {
        {
            Log.d("APP_FLOW", "Action: Navigating to Profile")
            navController.navigate(Screen.Profile.createRoute())
        }
    }

    val onNavigateToSettings = remember(navController) {
        {
            Log.d("APP_FLOW", "Action: Navigating to Settings")
            navController.navigate(Screen.Settings.route)
        }
    }

    val onNavigateToIdentity = remember(navController) {
        {
            navController.navigate(Screen.IdentitySelection.route)
        }
    }

    val onNavigateToAvatarEditor = remember(navController) {
        {
            navController.navigate(Screen.AvatarEditor.route)
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

    val onNavigateToModeration = remember(navController) {
        {
            navController.navigate(Screen.Moderation.route)
        }
    }

    val onLogout = remember(navController) {
        {
            navController.navigate(Screen.Login.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    val onBack = remember(navController) {
        {
            navController.popBackStack()
            Unit
        }
    }

    val onOnboardingFinished = remember(navController, onboardingManager, scope) {
        {
            scope.launch {
                onboardingManager.setOnboardingCompleted(emptySet())
                navController.navigate(Screen.Login.route) {
                    popUpTo(Screen.Onboarding.route) { inclusive = true }
                }
            }
            Unit
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
        composable(Screen.Onboarding.route) {
            OnboardingScreen(onOnboardingFinished = onOnboardingFinished)
        }
        composable(Screen.Login.route) {
            LoginScreen(onLoginSuccess = onLoginSuccess)
        }
        composable(Screen.Home.route) {
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
                onNavigateToNotifications = onNavigateToNotifications
            )
        }
        composable(Screen.Feed.route) {
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
                onNavigateToNotifications = onNavigateToNotifications
            )
        }
        composable(
            route = Screen.Profile.ROUTE_TEMPLATE,
            arguments = listOf(
                navArgument("userId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId")
            ProfileScreen(
                userId = userId,
                onLogout = onLogout,
                onBack = onBack,
                onEditIdentity = onNavigateToIdentity,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToReview = { draftId ->
                    navController.navigate(Screen.RecordingReview.createRoute(draftId))
                },
                onNavigateToComments = onNavigateToComments
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = onBack,
                onLogoutSuccess = onLogout,
                onNavigateToModeration = onNavigateToModeration
            )
        }
        composable(Screen.Moderation.route) {
            com.saurabh.artifact.ui.moderation.ModerationScreen(
                onBack = onBack
            )
        }
        composable(Screen.IdentitySelection.route) {
            IdentitySelectionScreen(
                onComplete = onBack,
                onBack = onBack,
                onEditAvatar = onNavigateToAvatarEditor
            )
        }
        composable(Screen.AvatarEditor.route) {
            AvatarEditorScreen(
                onBack = onBack,
                onComplete = onBack
            )
        }
        composable(Screen.PresenceBuilder.route) {
            com.saurabh.artifact.ui.avatar.PresenceBuilderScreen(
                onBack = onBack,
                onComplete = onBack
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
        composable(Screen.DraftList.route) {
            DraftListScreen(
                onBack = onBack,
                onReviewDraft = { draftId ->
                    navController.navigate(Screen.RecordingReview.createRoute(draftId))
                },
                onEditDraft = { draftId ->
                    navController.navigate(Screen.DraftEdit.createRoute(draftId))
                }
            )
        }
        composable(
            route = Screen.DraftEdit.route,
            arguments = listOf(navArgument("draftId") { type = NavType.StringType })
        ) { backStackEntry ->
            val draftId = URLDecoder.decode(backStackEntry.arguments?.getString("draftId") ?: "", StandardCharsets.UTF_8.toString())
            com.saurabh.artifact.ui.drafts.edit.DraftEditScreen(
                draftId = draftId,
                onBack = onBack,
                onReview = {
                    navController.navigate(Screen.RecordingReview.createRoute(draftId))
                },
                onPublish = {
                    navController.navigate(Screen.PublishPreparation.createRoute(draftId))
                }
            )
        }
        composable(
            route = Screen.PreRecordingWarning.route,
            arguments = listOf(
                navArgument("prompt") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val prompt = backStackEntry.arguments?.getString("prompt")?.let {
                URLDecoder.decode(it, StandardCharsets.UTF_8.toString())
            }
            com.saurabh.artifact.ui.recording.warning.PreRecordingWarningScreen(
                onContinue = {
                    navController.navigate(Screen.InstantRecord.createRoute(prompt)) {
                        popUpTo(Screen.PreRecordingWarning.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onCancel = { navController.popBackStack() },
                initialPrompt = prompt
            )
        }

        composable(
            route = Screen.InstantRecord.route,
            arguments = listOf(
                navArgument("prompt") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val prompt = backStackEntry.arguments?.getString("prompt")?.let {
                URLDecoder.decode(it, StandardCharsets.UTF_8.toString())
            }
            RecordingScreen(
                onFinished = { draftId ->
                    navController.navigate(Screen.PostRecordingDecision.createRoute(draftId)) {
                        popUpTo(Screen.InstantRecord.route) { inclusive = true }
                    }
                },
                onBack = onBack
            )
        }

        composable(
            route = Screen.PostRecordingDecision.route,
            arguments = listOf(navArgument("draftId") { type = NavType.StringType })
        ) { backStackEntry ->
            PostRecordingDecisionScreen(
                onReview = { draftId ->
                    navController.navigate(Screen.RecordingReview.createRoute(draftId)) {
                        popUpTo(Screen.PostRecordingDecision.route) { inclusive = true }
                    }
                },
                onSaveToDrafts = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.PostRecordingDecision.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.RecordingReview.route,
            arguments = listOf(navArgument("draftId") { type = NavType.StringType })
        ) { backStackEntry ->
            val draftId = URLDecoder.decode(backStackEntry.arguments?.getString("draftId") ?: "", StandardCharsets.UTF_8.toString())
            ReviewPlayerScreen(
                draftId = draftId,
                onReviewComplete = {
                    navController.navigate(Screen.PublishPreparation.createRoute(draftId)) {
                        popUpTo(Screen.RecordingReview.route) { inclusive = true }
                    }
                },
                onClose = onBack
            )
        }

        composable(
            route = Screen.PublishPreparation.route,
            arguments = listOf(navArgument("draftId") { type = NavType.StringType })
        ) { backStackEntry ->
            val draftId = URLDecoder.decode(backStackEntry.arguments?.getString("draftId") ?: "", StandardCharsets.UTF_8.toString())
            PublishPreparationScreen(
                draftId = draftId,
                onPublished = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onCancel = onBack
            )
        }

        composable(
            route = Screen.PublishApproval.route,
            arguments = listOf(navArgument("draftId") { type = NavType.StringType })
        ) { backStackEntry ->
            val draftId = URLDecoder.decode(backStackEntry.arguments?.getString("draftId") ?: "", StandardCharsets.UTF_8.toString())
            PublishApprovalScreen(
                onNavigateBack = onBack,
                onPublishSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
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
                onBack = onBack
            )
        }
    }
}
