package com.saurabh.artifact.navigation.features

import android.util.Log
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.saurabh.artifact.navigation.*
import com.saurabh.artifact.ui.drafts.list.DraftListScreen
import com.saurabh.artifact.ui.publish.studio.PublishingStudioScreen
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
                Log.d("NAV_TRACE", "[NAV_TRACE] DraftList -> PublishingStudio(draftId=$draftId)")
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
                Log.d("NAV_TRACE", "[NAV_TRACE] PublishingStudio(onFinish) -> Home")
                navController.navigate(Home) {
                    popUpTo(Home) { inclusive = true }
                }
            },
            onCancel = {
                Log.d("NAV_TRACE", "[NAV_TRACE] PublishingStudio(onCancel) -> popBackStack")
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
                // Navigate directly to the unified Publishing Studio
                Log.d("NAV_TRACE", "[NAV_TRACE] InstantRecord -> PublishingStudio(draftId=$draftId)")
                navController.navigate(PublishingStudio(draftId)) {
                    popUpTo(InstantRecord()) { inclusive = true }
                    launchSingleTop = true
                }
            },
            onBack = onBack
        )
    }

    composable<PublishingStudio> { backStackEntry ->
        val route = backStackEntry.toRoute<PublishingStudio>()
        PublishingStudioScreen(
            draftId = route.draftId,
            onFinish = {
                Log.d("NAV_TRACE", "[NAV_TRACE] PublishingStudio(onFinish) -> Home")
                navController.navigate(Home) {
                    popUpTo(Home) { inclusive = true }
                }
            },
            onCancel = {
                Log.d("NAV_TRACE", "[NAV_TRACE] PublishingStudio(onCancel) -> popBackStack")
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
