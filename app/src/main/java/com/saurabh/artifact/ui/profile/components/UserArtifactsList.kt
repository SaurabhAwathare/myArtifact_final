package com.saurabh.artifact.ui.profile.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ReactionType

fun LazyListScope.userArtifactsList(
    artifacts: List<Artifact>,
    currentlyPlayingArtifact: Artifact?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentPosition: Long,
    duration: Long,
    isSelf: Boolean,
    onPlayClick: (Artifact) -> Unit,
    onRename: (Artifact, String) -> Unit,
    onDelete: (Artifact) -> Unit,
    onViewComments: (Artifact) -> Unit
) {
    if (artifacts.isNotEmpty()) {
        items(artifacts, key = { it.id }) { artifact ->
            val isCurrent = currentlyPlayingArtifact?.id == artifact.id
            val progress by remember(isCurrent, currentPosition, duration) {
                derivedStateOf { 
                    if (isCurrent && duration > 0) currentPosition.toFloat() / duration else 0f 
                }
            }

            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                ProfileArtifactCard(
                    artifact = artifact,
                    isDraft = false,
                    isPlaying = isCurrent && isPlaying,
                    isBuffering = isCurrent && isBuffering,
                    progress = progress,
                    onPlayClick = { onPlayClick(artifact) },
                    onRename = { newTitle -> onRename(artifact, newTitle) },
                    onDelete = { onDelete(artifact) },
                    onViewComments = { onViewComments(artifact) }
                )
            }
        }
    }
}
