package com.saurabh.artifact.navigation.features

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.saurabh.artifact.navigation.Screen
import com.saurabh.artifact.ui.drafts.edit.DraftEditScreen
import com.saurabh.artifact.ui.drafts.list.DraftListScreen
import com.saurabh.artifact.ui.player.ReviewPlayerScreen
import com.saurabh.artifact.ui.publish.PublishApprovalScreen
import com.saurabh.artifact.ui.publish.PublishPreparationScreen
import com.saurabh.artifact.ui.recording.PostRecordingDecisionScreen
import com.saurabh.artifact.ui.recording.RecordingScreen
import com.saurabh.artifact.ui.recording.warning.PreRecordingWarningScreen
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

fun NavGraphBuilder.recordingNavigation(
    navController: NavHostController,
) {
    val onBack = {
        navController.popBackStack()
        Unit
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
        DraftEditScreen(
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
        PreRecordingWarningScreen(
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
    ) {
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
    ) {
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
    ) {
        PublishApprovalScreen(
            onNavigateBack = onBack,
            onPublishSuccess = {
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Home.route) { inclusive = true }
                }
            }
        )
    }
}
