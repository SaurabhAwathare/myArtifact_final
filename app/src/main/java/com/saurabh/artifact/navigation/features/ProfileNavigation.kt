package com.saurabh.artifact.navigation.features

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.navigation.*
import com.saurabh.artifact.ui.sigil.SigilRitualScreen
import com.saurabh.artifact.ui.identity.IdentitySelectionScreen
import com.saurabh.artifact.ui.moderation.ModerationScreen
import com.saurabh.artifact.ui.profile.ProfileScreen
import com.saurabh.artifact.ui.profile.ResonanceListScreen
import com.saurabh.artifact.ui.settings.SettingsScreen
import com.saurabh.artifact.ui.debug.DebugMenuScreen
import com.saurabh.artifact.ui.player.PlayerViewModel

fun NavGraphBuilder.profileNavigation(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    diagnosticLogger: DiagnosticLogger
) {
    val onBack = {
        navController.popBackStack()
        Unit
    }

    val onNavigateToIdentity = {
        navController.navigate(IdentitySelection)
    }

    val onNavigateToSettings = {
        navController.navigate(Settings)
    }

    val onNavigateToSigilRitual = {
        navController.navigate(SigilRitual)
    }

    val onLogout = {
        navController.navigate(Login) {
            // Pop the entire graph to clear authenticated history
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    composable<Profile> { backStackEntry ->
        val profile = backStackEntry.toRoute<Profile>()
        diagnosticLogger.debug(DiagnosticCategory.NAVIGATION, "NAVIGATE_TO_PROFILE", mapOf("targetUserId" to (profile.userId ?: "self")))
        ProfileScreen(
            userId = profile.userId,
            onLogout = onLogout,
            onBack = onBack,
            onEditIdentity = onNavigateToIdentity,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToReview = { draftId ->
                navController.navigate(PublishingStudio(draftId))
            },
            onNavigateToPublish = { draftId ->
                navController.navigate(PublishingStudio(draftId))
            },
            onNavigateToResonanceList = { id, type, title ->
                navController.navigate(ResonanceList(id, type, title))
            }
        )
    }

    composable<ResonanceList> {
        ResonanceListScreen(
            onBack = onBack,
            onUserClick = { clickedUserId ->
                navController.navigate(Profile(clickedUserId))
            }
        )
    }

    composable<Settings> {
        SettingsScreen(
            onBackClick = onBack,
            onLogoutSuccess = onLogout
        )
    }

    composable<DebugMenu> {
        DebugMenuScreen(
            onBackClick = onBack,
            onNavigateToModeration = { navController.navigate(Moderation) }
        )
    }

    composable<Moderation> {
        ModerationScreen(
            onBack = onBack
        )
    }

    composable<IdentitySelection> {
        IdentitySelectionScreen(
            onComplete = onBack,
            onBack = onBack,
            onEditSigil = onNavigateToSigilRitual
        )
    }

    composable<SigilRitual> {
        SigilRitualScreen(
            onNavigateBack = onBack
        )
    }
}
