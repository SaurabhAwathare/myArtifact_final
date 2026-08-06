package com.saurabh.artifact.ui.profile.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.saurabh.artifact.model.Artifact

fun LazyListScope.userArtifactsList(
    artifacts: List<Artifact>,
    currentlyPlayingArtifact: Artifact?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentPosition: Long,
    duration: Long,
    isSelf: Boolean,
    currentUserId: String? = null,
    onPlayClick: (Artifact) -> Unit,
    onRename: (Artifact, String) -> Unit,
    onDelete: (Artifact) -> Unit,
    onSaveClick: (Artifact) -> Unit = {},
    savedIds: Set<String> = emptySet(),
    emptyMessage: String? = null,
    onLoadMore: () -> Unit = {},
    isLoadingMore: Boolean = false,
    hasMore: Boolean = false
) {
    if (artifacts.isNotEmpty()) {
        items(artifacts, key = { it.id }) { artifact ->
            val index = artifacts.indexOf(artifact)
            if (index >= artifacts.size - 3 && hasMore && !isLoadingMore) {
                LaunchedEffect(artifacts.size) {
                    onLoadMore()
                }
            }

            val isCurrent = currentlyPlayingArtifact?.id == artifact.id
            val isSaved = savedIds.contains(artifact.id)
            val isOwner = currentUserId != null && artifact.userId == currentUserId
            val progress by remember(isCurrent, currentPosition, duration) {
                derivedStateOf { 
                    if (isCurrent && duration > 0) currentPosition.toFloat() / duration else 0f 
                }
            }

            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                ProfileArtifactCard(
                    artifact = artifact,
                    isDraft = false,
                    isOwner = isOwner || isSelf, // if isSelf is true, we assume ownership of published/draft lists
                    isPlaying = isCurrent && isPlaying,
                    isBuffering = isCurrent && isBuffering,
                    isSaved = isSaved,
                    progress = progress,
                    onPlayClick = { onPlayClick(artifact) },
                    onRename = { newTitle -> onRename(artifact, newTitle) },
                    onDelete = { onDelete(artifact) },
                    onUnsave = { onSaveClick(artifact) }
                )
            }
        }

        if (isLoadingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    } else if (emptyMessage != null) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 64.dp, horizontal = 32.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.material3.Text(
                    text = emptyMessage,
                    style = com.saurabh.artifact.ui.theme.ArtifactTheme.typography.bodyLarge,
                    color = com.saurabh.artifact.ui.theme.ArtifactTheme.colors.onSurfaceMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.alpha(0.6f)
                )
            }
        }
    }
}
