package com.saurabh.artifact.ui.profile.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.saurabh.artifact.ui.profile.models.DraftUiModel
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.CardShape
import com.saurabh.artifact.ui.theme.Spacing

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

/**
 * Informational banner reminding creators that their unpublished recordings are stored
 * locally on their current device.
 * Aligned with Stage 3.4 design system:
 * - 16dp CardShape container
 * - Solid Obsidian800 surface with Obsidian700 border
 * - Golden Aura icon and action button
 * - 48dp minimum interactive touch target
 */
@Composable
fun DraftStorageWarningBanner(
    draftCount: Int,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = ArtifactTheme.colors.surfaceHearth,
        border = BorderStroke(1.dp, ArtifactTheme.colors.waveformInactive)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Large)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = ArtifactTheme.colors.waveformActive,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.Small))
                Text(
                    text = "Your drafts are stored on this device",
                    style = ArtifactTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = ArtifactTheme.colors.onSurfaceMain
                )
            }

            Spacer(modifier = Modifier.height(Spacing.Small))

            val draftCountText = if (draftCount == 1) "1 unpublished recording" else "$draftCount unpublished recordings"
            Text(
                text = "You have $draftCountText. Uninstalling Artifact or clearing its app data may permanently delete them. To keep a backup, export your data in Settings and save the ZIP file somewhere safe before removing the app.",
                style = ArtifactTheme.typography.bodySmall,
                color = ArtifactTheme.colors.onSurfaceMuted,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(Spacing.Small))

            TextButton(
                onClick = onNavigateToSettings,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier
                    .align(Alignment.End)
                    .defaultMinSize(minHeight = 48.dp)
            ) {
                Text(
                    text = "Export in Settings",
                    style = ArtifactTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = ArtifactTheme.colors.waveformActive
                )
            }
        }
    }
}

@Preview(name = "Draft Storage Warning Banner")
@Composable
fun PreviewDraftStorageWarningBanner() {
    ArtifactTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            DraftStorageWarningBanner(
                draftCount = 3,
                onNavigateToSettings = {}
            )
        }
    }
}
