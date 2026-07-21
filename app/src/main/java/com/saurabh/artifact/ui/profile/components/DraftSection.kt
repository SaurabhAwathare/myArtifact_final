package com.saurabh.artifact.ui.profile.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.profile.models.DraftUiModel

fun LazyListScope.draftSection(
    drafts: List<DraftUiModel>,
    currentlyPlayingId: String?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayClick: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onPublishClick: (String) -> Unit = {},
    onDelete: (String) -> Unit
) {
    if (drafts.isNotEmpty()) {
        items(drafts, key = { it.artifact.id }) { draftUiModel ->
            val draft = draftUiModel.artifact
            val isCurrent = currentlyPlayingId == draft.id
            
            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                ProfileArtifactCard(
                    artifact = draft,
                    isDraft = true,
                    isOwner = true,
                    isPlaying = isCurrent && isPlaying,
                    isBuffering = isCurrent && isBuffering,
                    isListened = draftUiModel.isListened,
                    reviewProgress = draftUiModel.reviewProgress,
                    onPlayClick = { onPlayClick(draft.id) },
                    onRename = { newTitle -> onRename(draft.id, newTitle) },
                    onPublishClick = { onPublishClick(draft.id) },
                    onDelete = { onDelete(draft.id) }
                )
            }
        }
    }
}
