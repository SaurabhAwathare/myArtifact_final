package com.saurabh.artifact.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/**
 * No-op implementation of debug routes for release builds.
 * This ensures that production code can call registerDebugRoutes without
 * bringing in the debug-only UI components.
 */
fun NavGraphBuilder.registerDebugRoutes(
    navController: NavHostController,
    onBack: () -> Unit
) {
    // No-op in release
}
