package com.saurabh.artifact.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.saurabh.artifact.ui.debug.DebugMenuScreen
import com.saurabh.artifact.navigation.DebugMenu
import com.saurabh.artifact.navigation.Moderation

/**
 * Registers debug-only routes.
 * Included in debug builds to enable the Debug Menu.
 */
fun NavGraphBuilder.registerDebugRoutes(
    navController: NavHostController,
    onBack: () -> Unit
) {
    composable<DebugMenu> {
        DebugMenuScreen(
            onBackClick = onBack,
            onNavigateToModeration = { navController.navigate(Moderation) }
        )
    }
}
