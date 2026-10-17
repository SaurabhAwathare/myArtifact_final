package com.saurabh.artifact.ui.profile.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.ui.profile.models.DraftUiModel
import com.saurabh.artifact.ui.theme.ArtifactTheme

fun LazyListScope.draftSection(
    drafts: List<DraftUiModel>,
    currentlyPlayingId: String?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayClick: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onPublishClick: (String) -> Unit = {},
    onDelete: (String) -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    if (drafts.isNotEmpty()) {
        item(key = "local_draft_storage_warning_banner") {
            DraftStorageWarningBanner(
                draftCount = drafts.size,
                onNavigateToSettings = onNavigateToSettings,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

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

@Composable
fun DraftStorageWarningBanner(
    draftCount: Int,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ArtifactTheme.colors.surfaceHearth.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Your drafts are stored on this device",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ArtifactTheme.colors.onSurfaceMain
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            val draftCountText = if (draftCount == 1) "1 unpublished recording" else "$draftCount unpublished recordings"
            Text(
                text = "You have $draftCountText. Uninstalling Artifact or clearing its app data may permanently delete them. To keep a backup, export your data in Settings and save the ZIP file somewhere safe before removing the app.",
                style = MaterialTheme.typography.bodySmall,
                color = ArtifactTheme.colors.onSurfaceMuted,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onNavigateToSettings,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    text = "Export in Settings",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
