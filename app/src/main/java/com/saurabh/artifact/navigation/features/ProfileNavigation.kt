package com.saurabh.artifact.navigation.features

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.saurabh.artifact.navigation.*
import com.saurabh.artifact.ui.avatar.AvatarEditorScreen
import com.saurabh.artifact.ui.identity.IdentitySelectionScreen
import com.saurabh.artifact.ui.moderation.ModerationScreen
import com.saurabh.artifact.ui.profile.ProfileScreen
import com.saurabh.artifact.ui.profile.ResonanceListScreen
import com.saurabh.artifact.ui.settings.SettingsScreen
import com.saurabh.artifact.ui.debug.DebugMenuScreen
import com.saurabh.artifact.ui.player.PlayerViewModel
import com.saurabh.artifact.model.PlaybackSource

fun NavGraphBuilder.profileNavigation(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
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

    val onNavigateToAvatarEditor = {
        navController.navigate(AvatarEditor)
    }

    val onNavigateToComments = { artifactId: String, ownerId: String ->
        navController.navigate(Comments(artifactId, ownerId))
    }

    val onLogout = {
        navController.navigate(Login) {
            popUpTo(Login) { inclusive = true }
            launchSingleTop = true
        }
    }

    composable<Profile> { backStackEntry ->
        val profile = backStackEntry.toRoute<Profile>()
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
            },
            onNavigateToComments = onNavigateToComments
        )
    }

    composable<ResonanceList> {
        // We don't strictly need to extract them here if ResonanceListScreen uses ViewModel with SavedStateHandle
        // but for consistency:
        // val resonance = it.toRoute<ResonanceList>()
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
            onEditAvatar = onNavigateToAvatarEditor
        )
    }

    composable<AvatarEditor> {
        AvatarEditorScreen(
            onBack = onBack,
            onComplete = onBack
        )
    }
}
