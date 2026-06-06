package com.saurabh.artifact.navigation.features

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.saurabh.artifact.navigation.Screen
import com.saurabh.artifact.ui.avatar.AvatarEditorScreen
import com.saurabh.artifact.ui.avatar.PresenceBuilderScreen
import com.saurabh.artifact.ui.identity.IdentitySelectionScreen
import com.saurabh.artifact.ui.moderation.ModerationScreen
import com.saurabh.artifact.ui.profile.ProfileScreen
import com.saurabh.artifact.ui.profile.ResonanceListScreen
import com.saurabh.artifact.ui.settings.SettingsScreen

fun NavGraphBuilder.profileNavigation(
    navController: NavHostController,
) {
    val onBack = {
        navController.popBackStack()
        Unit
    }

    val onNavigateToIdentity = {
        navController.navigate(Screen.IdentitySelection.route)
    }

    val onNavigateToSettings = {
        navController.navigate(Screen.Settings.route)
    }

    val onNavigateToAvatarEditor = {
        navController.navigate(Screen.AvatarEditor.route)
    }

    val onNavigateToComments = { artifactId: String, ownerId: String ->
        navController.navigate(Screen.Comments.createRoute(artifactId, ownerId))
    }

    val onLogout = {
        navController.navigate(Screen.Login.route) {
            popUpTo(Screen.Login.route) { inclusive = true }
            launchSingleTop = true
        }
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
            onNavigateToResonanceList = { id, type, title ->
                navController.navigate(Screen.ResonanceList.createRoute(id, type, title))
            },
            onNavigateToComments = onNavigateToComments
        )
    }

    composable(
        route = Screen.ResonanceList.route,
        arguments = listOf(
            navArgument("userId") { type = NavType.StringType },
            navArgument("type") { type = NavType.StringType },
            navArgument("title") { 
                type = NavType.StringType
                nullable = true
                defaultValue = "Resonators"
            }
        )
    ) {
        ResonanceListScreen(
            onBack = onBack,
            onUserClick = { clickedUserId ->
                navController.navigate(Screen.Profile.createRoute(clickedUserId))
            }
        )
    }

    composable(Screen.Settings.route) {
        SettingsScreen(
            onBackClick = onBack,
            onLogoutSuccess = onLogout
        )
    }

    composable(Screen.Moderation.route) {
        ModerationScreen(
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
        PresenceBuilderScreen(
            onBack = onBack,
            onComplete = onBack
        )
    }
}
