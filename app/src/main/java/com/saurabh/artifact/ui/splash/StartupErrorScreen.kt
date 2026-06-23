package com.saurabh.artifact.ui.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.saurabh.artifact.ui.components.base.AppButton
import com.saurabh.artifact.ui.components.base.AppEmptyState
import com.saurabh.artifact.ui.theme.Obsidian950

/**
 * Dedicated error screen for fatal startup/registration failures.
 * Part of the Self-Healing system to provide actionable recovery paths.
 */
@Composable
fun StartupErrorScreen(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian950),
        contentAlignment = Alignment.Center
    ) {
        AppEmptyState(
            title = "The path is blocked",
            description = message,
            emoji = "🌑",
            action = {
                AppButton(
                    text = "Try again",
                    onClick = onRetry
                )
            }
        )
    }
}
