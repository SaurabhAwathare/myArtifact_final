package com.saurabh.artifact.navigation.features

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.saurabh.artifact.navigation.*
import com.saurabh.artifact.ui.drafts.edit.DraftEditScreen
import com.saurabh.artifact.ui.drafts.list.DraftListScreen
import com.saurabh.artifact.ui.player.PlayerViewModel
import com.saurabh.artifact.ui.publish.PublishApprovalScreen
import com.saurabh.artifact.ui.publish.PublishPreparationScreen
import com.saurabh.artifact.ui.recording.PostRecordingDecisionScreen
import com.saurabh.artifact.ui.recording.RecordingScreen
import com.saurabh.artifact.ui.recording.warning.PreRecordingWarningScreen

fun NavGraphBuilder.recordingNavigation(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
) {
    val onBack = {
        navController.popBackStack()
        Unit
    }

    composable<DraftList> {
        DraftListScreen(
            onBack = onBack,
            onReviewDraft = { draftId ->
                // Ensure we handle potential race condition if playArtifactById is called before ViewModel is fully ready
                playerViewModel.playArtifactById(draftId)
                playerViewModel.setExpanded(true)
            },
            onEditDraft = { draftId ->
                navController.navigate(DraftEdit(draftId))
            },
            onPublishDraft = { draftId ->
                navController.navigate(PublishPreparation(draftId))
            }
        )
    }

    composable<DraftEdit> { backStackEntry ->
        val route = backStackEntry.toRoute<DraftEdit>()
        DraftEditScreen(
            draftId = route.draftId,
            onBack = onBack,
            onReview = {
                // Phase 2: Route through Global Player
                playerViewModel.playArtifactById(route.draftId)
                playerViewModel.setExpanded(true)
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
                // Phase 2: Route through Global Player
                playerViewModel.playArtifactById(draftId)
                playerViewModel.setExpanded(true)
                
                // Return home so the player is visible on the home screen
                navController.navigate(Home) {
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
