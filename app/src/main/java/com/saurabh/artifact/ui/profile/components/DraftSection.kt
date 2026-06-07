package com.saurabh.artifact.ui.profile.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.Artifact

fun LazyListScope.draftSection(
    drafts: List<ArtifactDraftEntity>,
    currentlyPlayingId: String?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayClick: (ArtifactDraftEntity) -> Unit,
    onRename: (ArtifactDraftEntity, String) -> Unit,
    onDelete: (ArtifactDraftEntity) -> Unit
) {
    if (drafts.isNotEmpty()) {
        items(drafts, key = { it.id }) { draft ->
            val isCurrent = currentlyPlayingId == draft.id
            
            val tempArtifact = Artifact(
                id = draft.id,
                title = draft.title ?: "Unfinished Recording",
                author = com.saurabh.artifact.model.AuthorSnapshot(name = "Private Draft"),
                durationMs = draft.durationMs,
                status = com.saurabh.artifact.model.ArtifactStatus.DRAFT,
                amplitudeData = draft.amplitudeData,
                createdAt = com.google.firebase.Timestamp(java.util.Date(draft.createdAt))
            )

            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                ProfileArtifactCard(
                    artifact = tempArtifact,
                    isDraft = true,
                    isOwner = true,
                    isPlaying = isCurrent && isPlaying,
                    isBuffering = isCurrent && isBuffering,
                    onPlayClick = { onPlayClick(draft) },
                    onRename = { newTitle -> onRename(draft, newTitle) },
                    onDelete = { onDelete(draft) },
                    onViewComments = null // Drafts don't have comments
                )
            }
        }
    }
}
