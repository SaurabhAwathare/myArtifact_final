package com.saurabh.artifact.ui.drafts.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.DraftWithUpload
import com.saurabh.artifact.ui.drafts.components.DraftCard
import com.saurabh.artifact.ui.drafts.components.PublishingDraftCard
import com.saurabh.artifact.ui.theme.*

/**
 * Stage 3.4 — Standalone Draft List Presentation
 *
 * Provides a serene, focused overview of an Artifact creator's unpublished recordings
 * and active publishing tasks. Aligned with the Artifact Design System:
 * - Solid Obsidian950 (surfaceLoom) screen canvas
 * - Unified 16dp CardShape containers with Obsidian800 surfaces and Obsidian700 borders
 * - WCAG AA/AAA typography hierarchy in ReflectionWhite and MistGray
 * - Non-destructive and destructive action semantic clarity
 * - 48dp minimum interactive touch targets across all controls
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftListScreen(
    onBack: () -> Unit,
    onNavigateToStudio: (String) -> Unit,
    viewModel: DraftListViewModel = hiltViewModel()
) {
    val drafts by viewModel.drafts.collectAsState()
    val publishingDrafts by viewModel.publishingDrafts.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isBuffering by viewModel.isBuffering.collectAsState()
    val currentlyPlayingDraftId by viewModel.currentlyPlayingDraftId.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()

    var draftToDelete by remember { mutableStateOf<DraftWithUpload?>(null) }

    LaunchedEffect(viewModel.events) {
        viewModel.events.collect { event ->
            when (event) {
                is DraftListUiEvent.NavigateToReview -> onNavigateToStudio(event.draftId)
                is DraftListUiEvent.NavigateToEdit -> onNavigateToStudio(event.draftId)
                is DraftListUiEvent.NavigateToPublish -> onNavigateToStudio(event.draftId)
            }
        }
    }

    Scaffold(
        containerColor = ArtifactTheme.colors.surfaceLoom,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Local Drafts",
                        style = ArtifactTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = ArtifactTheme.colors.onSurfaceMain
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back",
                            tint = ArtifactTheme.colors.onSurfaceMain,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ArtifactTheme.colors.surfaceLoom,
                    titleContentColor = ArtifactTheme.colors.onSurfaceMain,
                    navigationIconContentColor = ArtifactTheme.colors.onSurfaceMain
                )
            )
        }
    ) { padding ->
        if (drafts.isEmpty() && publishingDrafts.isEmpty()) {
            // Calm, reflective Empty State
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(Spacing.ExtraLarge),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Large)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(ArtifactTheme.colors.surfaceHearth)
                            .border(1.dp, ArtifactTheme.colors.waveformInactive, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MicNone,
                            contentDescription = null,
                            tint = ArtifactTheme.colors.onSurfaceMuted,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Text(
                        text = "No Drafts Waiting",
                        style = ArtifactTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = ArtifactTheme.colors.onSurfaceMain
                    )

                    Text(
                        text = "Your unfinished recordings and reflections will rest here until you're ready to review or release them.",
                        style = ArtifactTheme.typography.bodyMedium,
                        color = ArtifactTheme.colors.onSurfaceMuted,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(Spacing.Medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                // Section: Active Publishing Tasks
                if (publishingDrafts.isNotEmpty()) {
                    item(key = "header_publishing") {
                        Text(
                            text = "PUBLISHING",
                            style = ArtifactTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.2.sp
                            ),
                            color = ArtifactTheme.colors.onSurfaceAura,
                            modifier = Modifier.padding(bottom = Spacing.Small)
                        )
                    }

                    items(publishingDrafts, key = { "pub_${it.draft.id}" }) { draftWithUpload ->
                        PublishingDraftCard(
                            draftWithUpload = draftWithUpload,
                            onRetry = { viewModel.retryPublish(draftWithUpload) },
                            onCancel = { viewModel.cancelPublish(draftWithUpload) }
                        )
                    }

                    item(key = "divider_publishing") {
                        HorizontalDivider(
                            color = ArtifactTheme.colors.waveformInactive,
                            modifier = Modifier.padding(vertical = Spacing.Small)
                        )
                    }
                }

                // Section: Local Drafts
                if (drafts.isNotEmpty()) {
                    item(key = "header_local_drafts") {
                        Text(
                            text = "LOCAL DRAFTS",
                            style = ArtifactTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.2.sp
                            ),
                            color = ArtifactTheme.colors.onSurfaceMuted,
                            modifier = Modifier.padding(bottom = Spacing.Small)
                        )
                    }

                    items(drafts, key = { it.draft.id }) { draftWithUpload ->
                        val draft = draftWithUpload.draft
                        val isCurrentDraft = currentlyPlayingDraftId == draft.id

                        DraftCard(
                            draftWithUpload = draftWithUpload,
                            isPlaying = isCurrentDraft && isPlaying,
                            isBuffering = isCurrentDraft && isBuffering,
                            playbackProgress = if (isCurrentDraft) playbackProgress else 0f,
                            onPlayPauseClick = { viewModel.playDraftAudio(draft) },
                            onClick = { viewModel.onDraftClicked(draftWithUpload) },
                            onEdit = { viewModel.onEditClicked(draft.id) },
                            onPublish = { viewModel.onPublishClicked(draft.id) },
                            onDelete = {
                                draftToDelete = draftWithUpload
                            },
                            onRetryProcessing = { viewModel.retryProcessing(draftWithUpload) }
                        )
                    }
                }
            }
        }

        // Delete Draft Confirmation Dialog
        draftToDelete?.let { draftWithUpload ->
            AlertDialog(
                onDismissRequest = { draftToDelete = null },
                shape = ModalShape,
                containerColor = ArtifactTheme.colors.surfaceHearth,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        tint = ArtifactTheme.colors.actionDestructive,
                        modifier = Modifier.size(28.dp)
                    )
                },
                title = {
                    Text(
                        text = "Delete Draft",
                        style = ArtifactTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = ArtifactTheme.colors.onSurfaceMain
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to delete this draft? The local recording will be permanently deleted from this device and cannot be recovered.",
                        style = ArtifactTheme.typography.bodyMedium,
                        color = ArtifactTheme.colors.onSurfaceMuted,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteDraft(draftWithUpload)
                            draftToDelete = null
                        },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(
                            text = "Delete",
                            style = ArtifactTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = ArtifactTheme.colors.actionDestructive
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { draftToDelete = null },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            style = ArtifactTheme.typography.labelLarge,
                            color = ArtifactTheme.colors.onSurfaceMuted
                        )
                    }
                }
            )
        }
    }
}

/**
 * Backward compatibility alias delegating to [PublishingDraftCard].
 */
@Composable
fun PublishingItem(
    draftWithUpload: DraftWithUpload,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    PublishingDraftCard(
        draftWithUpload = draftWithUpload,
        onRetry = onRetry,
        onCancel = onCancel
    )
}

/**
 * Backward compatibility alias delegating to [DraftCard].
 */
@Composable
fun DraftItem(
    draftWithUpload: DraftWithUpload,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onPublish: () -> Unit,
    onDelete: () -> Unit,
    onRetryProcessing: () -> Unit
) {
    DraftCard(
        draftWithUpload = draftWithUpload,
        onClick = onClick,
        onEdit = onEdit,
        onPublish = onPublish,
        onDelete = onDelete,
        onRetryProcessing = onRetryProcessing
    )
}

// --- Previews ---

@Preview(name = "Draft List Screen - Populated")
@Composable
fun PreviewDraftListScreenPopulated() {
    ArtifactTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ArtifactTheme.colors.surfaceLoom)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(Spacing.Medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                item {
                    Text(
                        text = "PUBLISHING",
                        style = ArtifactTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp
                        ),
                        color = ArtifactTheme.colors.onSurfaceAura
                    )
                }

                item {
                    PublishingDraftCard(
                        draftWithUpload = DraftWithUpload(
                            draft = ArtifactDraftEntity(
                                id = "pub_preview",
                                userId = "user_preview",
                                localAudioPath = "/path/to/audio.m4a",
                                title = "Releasing Evening Reflection",
                                uploadedBytes = 450000L,
                                totalBytes = 1000000L,
                                status = DraftStatus(publication = SyncStatus.Uploading)
                            ),
                            uploadTask = null
                        ),
                        onRetry = {},
                        onCancel = {}
                    )
                }

                item {
                    HorizontalDivider(
                        color = ArtifactTheme.colors.waveformInactive,
                        modifier = Modifier.padding(vertical = Spacing.Small)
                    )
                }

                item {
                    Text(
                        text = "LOCAL DRAFTS",
                        style = ArtifactTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp
                        ),
                        color = ArtifactTheme.colors.onSurfaceMuted
                    )
                }

                item {
                    DraftCard(
                        draftWithUpload = DraftWithUpload(
                            draft = ArtifactDraftEntity(
                                id = "draft_preview_1",
                                userId = "user_preview",
                                localAudioPath = "/path/to/audio.m4a",
                                title = "Morning Reflections in Silence",
                                durationMs = 45000L,
                                createdAt = System.currentTimeMillis() - 7200000L,
                                lifecycle = ArtifactLifecycle.REVIEW_REQUIRED,
                                reviewProgress = 0.5f,
                                emotion = Emotion.CALM,
                                amplitudeData = listOf(0.3f, 0.5f, 0.8f, 0.4f, 0.6f, 0.9f, 0.3f)
                            ),
                            uploadTask = null
                        ),
                        onClick = {},
                        onEdit = {},
                        onPublish = {},
                        onDelete = {},
                        onRetryProcessing = {}
                    )
                }

                item {
                    DraftCard(
                        draftWithUpload = DraftWithUpload(
                            draft = ArtifactDraftEntity(
                                id = "draft_preview_2",
                                userId = "user_preview",
                                localAudioPath = "/path/to/audio.m4a",
                                title = "Thoughts on Impermanence",
                                durationMs = 72000L,
                                createdAt = System.currentTimeMillis() - 86400000L,
                                lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
                                isListened = true,
                                reviewProgress = 1.0f,
                                emotion = Emotion.GRATEFUL,
                                amplitudeData = listOf(0.4f, 0.7f, 0.6f, 0.8f, 0.5f, 0.2f)
                            ),
                            uploadTask = null
                        ),
                        onClick = {},
                        onEdit = {},
                        onPublish = {},
                        onDelete = {},
                        onRetryProcessing = {}
                    )
                }
            }
        }
    }
}
