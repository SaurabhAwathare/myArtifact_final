package com.saurabh.artifact.ui.components.state

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.saurabh.artifact.ui.components.base.AppButton
import com.saurabh.artifact.ui.components.base.AppEmptyState

/**
 * A calming empty state for the feed when no artifacts are available.
 */
@Composable
fun EmptyFeedState(
    onRecordClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedEmotion: String? = null
) {
    val description = if (selectedEmotion != null) {
        "No artifacts found for $selectedEmotion. Share a fragment of your day, anonymously and safely."
    } else {
        "Your voice could be the first light here. Share a fragment of your day, anonymously and safely."
    }

    AppEmptyState(
        title = if (selectedEmotion != null) "No $selectedEmotion artifacts" else "The forest is resting",
        description = description,
        emoji = "🌿",
        modifier = modifier,
        action = {
            AppButton(
                text = "Leave a trace",
                onClick = onRecordClick
            )
        }
    )
}

/**
 * An empty state shown when loading the feed fails due to an error.
 */
@Composable
fun FeedErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null
) {
    AppEmptyState(
        title = "The path is blocked",
        description = message ?: "Unable to load artifacts right now. Please check your connection and try again.",
        emoji = "🌑",
        modifier = modifier,
        action = {
            AppButton(
                text = "Try again",
                onClick = onRetry
            )
        }
    )
}
