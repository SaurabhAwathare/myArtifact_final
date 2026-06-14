package com.saurabh.artifact.navigation.features

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.saurabh.artifact.navigation.*
import com.saurabh.artifact.ui.drafts.list.DraftListScreen
import com.saurabh.artifact.ui.publish.studio.PublishingStudioScreen
import com.saurabh.artifact.ui.recording.PostRecordingDecisionScreen
import com.saurabh.artifact.ui.recording.RecordingScreen
import com.saurabh.artifact.ui.recording.warning.PreRecordingWarningScreen

fun NavGraphBuilder.recordingNavigation(
    navController: NavHostController,
) {
    val onBack = {
        navController.popBackStack()
        Unit
    }

    composable<DraftList> {
        DraftListScreen(
            onBack = onBack,
            onNavigateToStudio = { draftId ->
                navController.navigate(PublishingStudio(draftId))
            }
        )
    }

    composable<DraftEdit> { backStackEntry ->
        val route = backStackEntry.toRoute<DraftEdit>()
        PublishingStudioScreen(
            draftId = route.draftId,
            onFinish = {
                navController.navigate(Home) {
                    popUpTo(Home) { inclusive = true }
                }
            },
            onCancel = {
                navController.popBackStack()
            }
        )
    }

    composable<PreRecordingWarning> { backStackEntry ->
        val route = backStackEntry.toRoute<PreRecordingWarning>()
        PreRecordingWarningScreen(
            onContinue = {
                navController.navigate(InstantRecord(route.prompt)) {
                    popUpTo(PreRecordingWarning()) { inclusive = true }
                    launchSingleTop = true
                }
            },
            onCancel = { navController.popBackStack() },
            initialPrompt = route.prompt
        )
    }

    composable<InstantRecord> {
        RecordingScreen(
            onFinished = { draftId ->
                navController.navigate(PostRecordingDecision(draftId)) {
                    popUpTo(InstantRecord()) { inclusive = true }
                }
            },
            onBack = onBack
        )
    }

    composable<PostRecordingDecision> { backStackEntry ->
        val route = backStackEntry.toRoute<PostRecordingDecision>()
        PostRecordingDecisionScreen(
            onReview = { draftId ->
                // Enter the unified Publishing Studio
                navController.navigate(PublishingStudio(draftId)) {
                    popUpTo(PostRecordingDecision(route.draftId)) { inclusive = true }
                }
            },
            onSaveToDrafts = {
                navController.navigate(Home) {
                    popUpTo(PostRecordingDecision(route.draftId)) { inclusive = true }
                }
            }
        )
    }

    composable<PublishingStudio> { backStackEntry ->
        val route = backStackEntry.toRoute<PublishingStudio>()
        PublishingStudioScreen(
            draftId = route.draftId,
            onFinish = {
                navController.navigate(Home) {
                    popUpTo(Home) { inclusive = true }
                }
            },
            onCancel = {
                navController.popBackStack()
            }
        )
    }

    composable<PublishPreparation> { backStackEntry ->
        val route = backStackEntry.toRoute<PublishPreparation>()
        PublishingStudioScreen(
            draftId = route.draftId,
            onFinish = {
                navController.navigate(Home) {
                    popUpTo(Home) { inclusive = true }
                }
            },
            onCancel = {
                navController.popBackStack()
            }
        )
    }

    composable<PublishApproval> { backStackEntry ->
        val route = backStackEntry.toRoute<PublishApproval>()
        PublishingStudioScreen(
            draftId = route.draftId,
            onFinish = {
                navController.navigate(Home) {
                    popUpTo(Home) { inclusive = true }
                }
            },
            onCancel = {
                navController.popBackStack()
            }
        )
    }
}
