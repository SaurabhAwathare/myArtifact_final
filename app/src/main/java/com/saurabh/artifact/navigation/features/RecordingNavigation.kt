package com.saurabh.artifact.navigation.features

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.saurabh.artifact.navigation.*
import com.saurabh.artifact.ui.drafts.edit.DraftEditScreen
import com.saurabh.artifact.ui.drafts.list.DraftListScreen
import com.saurabh.artifact.ui.player.ReviewPlayerScreen
import com.saurabh.artifact.ui.publish.PublishApprovalScreen
import com.saurabh.artifact.ui.publish.PublishPreparationScreen
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
            onReviewDraft = { draftId ->
                navController.navigate(RecordingReview(draftId))
            },
            onEditDraft = { draftId ->
                navController.navigate(DraftEdit(draftId))
            }
        )
    }

    composable<DraftEdit> { backStackEntry ->
        val route = backStackEntry.toRoute<DraftEdit>()
        DraftEditScreen(
            draftId = route.draftId,
            onBack = onBack,
            onReview = {
                navController.navigate(RecordingReview(route.draftId))
            },
            onPublish = {
                navController.navigate(PublishPreparation(route.draftId))
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
                navController.navigate(RecordingReview(draftId)) {
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

    composable<RecordingReview> { backStackEntry ->
        val route = backStackEntry.toRoute<RecordingReview>()
        ReviewPlayerScreen(
            draftId = route.draftId,
            onReviewComplete = {
                navController.navigate(PublishPreparation(route.draftId)) {
                    popUpTo(RecordingReview(route.draftId)) { inclusive = true }
                }
            },
            onClose = onBack
        )
    }

    composable<PublishPreparation> { backStackEntry ->
        val route = backStackEntry.toRoute<PublishPreparation>()
        PublishPreparationScreen(
            draftId = route.draftId,
            onPublished = {
                navController.navigate(Home) {
                    popUpTo(Home) { inclusive = true }
                }
            },
            onCancel = onBack
        )
    }

    composable<PublishApproval> {
        PublishApprovalScreen(
            onNavigateBack = onBack,
            onPublishSuccess = {
                navController.navigate(Home) {
                    popUpTo(Home) { inclusive = true }
                }
            }
        )
    }
}
