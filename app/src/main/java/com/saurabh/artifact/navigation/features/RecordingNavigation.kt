package com.saurabh.artifact.navigation.features

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.navigation.*
import com.saurabh.artifact.ui.drafts.list.DraftListScreen
import com.saurabh.artifact.ui.publish.studio.PublishingStudioScreen
import com.saurabh.artifact.ui.recording.decision.PostRecordingDecisionScreen
import com.saurabh.artifact.ui.recording.RecordingScreen
import com.saurabh.artifact.ui.recording.warning.PreRecordingWarningScreen

fun NavGraphBuilder.recordingNavigation(
    navController: NavHostController,
    diagnosticLogger: DiagnosticLogger
) {
    val onBack = {
        navController.popBackStack()
        Unit
    }

    composable<DraftList> {
        DraftListScreen(
            onBack = onBack,
            onNavigateToStudio = { draftId ->
                diagnosticLogger.info(DiagnosticCategory.NAVIGATION, "NAVIGATE_TO_PUBLISHING_STUDIO", mapOf(LogKeys.DRAFT_ID to draftId, "source" to "DraftList"))
                navController.navigate(PublishingStudio(draftId)) {
                    launchSingleTop = true
                }
            }
        )
    }

    composable<DraftEdit> { backStackEntry ->
        val route = backStackEntry.toRoute<DraftEdit>()
        PublishingStudioScreen(
            draftId = route.draftId,
            onFinish = {
                diagnosticLogger.info(DiagnosticCategory.NAVIGATION, "NAVIGATE_FROM_STUDIO", mapOf(LogKeys.DRAFT_ID to route.draftId, "action" to "FINISH"))
                navController.navigate(Home) {
                    popUpTo(Home) { inclusive = true }
                }
            },
            onCancel = {
                diagnosticLogger.info(DiagnosticCategory.NAVIGATION, "NAVIGATE_FROM_STUDIO", mapOf(LogKeys.DRAFT_ID to route.draftId, "action" to "CANCEL"))
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
                // Navigate to the post-recording decision screen
                diagnosticLogger.info(DiagnosticCategory.NAVIGATION, "NAVIGATE_TO_POST_RECORDING_DECISION", mapOf(LogKeys.DRAFT_ID to draftId, "source" to "InstantRecord"))
                navController.navigate(PostRecordingDecision(draftId)) {
                    popUpTo(InstantRecord()) { inclusive = true }
                    launchSingleTop = true
                }
            },
            onBack = onBack
        )
    }

    composable<PostRecordingDecision> { backStackEntry ->
        val route = backStackEntry.toRoute<PostRecordingDecision>()
        PostRecordingDecisionScreen(
            draftId = route.draftId,
            onReview = { draftId ->
                diagnosticLogger.info(DiagnosticCategory.NAVIGATION, "NAVIGATE_TO_PUBLISHING_STUDIO", mapOf(LogKeys.DRAFT_ID to draftId, "source" to "PostRecordingDecision"))
                navController.navigate(PublishingStudio(draftId)) {
                    popUpTo(PostRecordingDecision(route.draftId)) { inclusive = true }
                    launchSingleTop = true
                }
            },
            onSaveAsDraft = {
                diagnosticLogger.info(DiagnosticCategory.NAVIGATION, "NAVIGATE_SAVE_AS_DRAFT", mapOf(LogKeys.DRAFT_ID to route.draftId))
                navController.popBackStack()
            }
        )
    }

    composable<PublishingStudio> { backStackEntry ->
        val route = backStackEntry.toRoute<PublishingStudio>()
        PublishingStudioScreen(
            draftId = route.draftId,
            onFinish = {
                diagnosticLogger.info(DiagnosticCategory.NAVIGATION, "NAVIGATE_FROM_STUDIO", mapOf(LogKeys.DRAFT_ID to route.draftId, "action" to "FINISH"))
                navController.navigate(Home) {
                    popUpTo(Home) { inclusive = true }
                }
            },
            onCancel = {
                diagnosticLogger.info(DiagnosticCategory.NAVIGATION, "NAVIGATE_FROM_STUDIO", mapOf(LogKeys.DRAFT_ID to route.draftId, "action" to "CANCEL"))
                navController.popBackStack()
            }
        )
    }

    // Consolidated: PublishPreparation and PublishApproval now use PublishingStudio route
    // These remain as aliases if needed for deep linking, but redirect to the same Screen
    composable<PublishPreparation> { backStackEntry ->
        val route = backStackEntry.toRoute<PublishPreparation>()
        navController.navigate(PublishingStudio(route.draftId)) {
            popUpTo(PublishPreparation(route.draftId)) { inclusive = true }
        }
    }

    composable<PublishApproval> { backStackEntry ->
        val route = backStackEntry.toRoute<PublishApproval>()
        navController.navigate(PublishingStudio(route.draftId)) {
            popUpTo(PublishApproval(route.draftId)) { inclusive = true }
        }
    }
}
