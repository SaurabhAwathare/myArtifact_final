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
    modifier: Modifier = Modifier
) {
    AppEmptyState(
        title = "The forest is resting",
        description = "Your voice could be the first light here. Share a fragment of your day, anonymously and safely.",
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
