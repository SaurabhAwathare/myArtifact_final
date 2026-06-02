package com.saurabh.artifact.ui.drafts.list

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.repository.DraftWithUpload
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftListScreen(
    onBack: () -> Unit,
    onReviewDraft: (String) -> Unit,
    onEditDraft: (String) -> Unit,
    viewModel: DraftListViewModel = hiltViewModel()
) {
    val drafts by viewModel.drafts.collectAsState()
    val publishingDrafts by viewModel.publishingDrafts.collectAsState()
    var draftToDelete by remember { mutableStateOf<DraftWithUpload?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local Drafts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (drafts.isEmpty() && publishingDrafts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No drafts found", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (publishingDrafts.isNotEmpty()) {
                    item {
                        Text(
                            "Publishing",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(publishingDrafts, key = { "pub_${it.draft.id}" }) { draftWithUpload ->
                        PublishingItem(
                            draftWithUpload = draftWithUpload,
                            onRetry = { viewModel.retryPublish(draftWithUpload) },
                            onCancel = { viewModel.cancelPublish(draftWithUpload) }
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                    item { HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)) }
                    item { Spacer(Modifier.height(8.dp)) }
                }

                if (drafts.isNotEmpty()) {
                    item {
                        Text(
                            "Local Drafts",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(drafts, key = { it.draft.id }) { draftWithUpload ->
                        DraftItem(
                            draftWithUpload = draftWithUpload,
                            onClick = { 
                                if (draftWithUpload.draft.syncState == com.saurabh.artifact.model.SyncState.RECOVERING) {
                                    // Trigger processing for interrupted draft
                                    viewModel.playDraft(draftWithUpload) // This now triggers processing too
                                } else {
                                    onReviewDraft(draftWithUpload.draft.id)
                                }
                            },
                            onEdit = { onEditDraft(draftWithUpload.draft.id) },
                            onDelete = {
                                draftToDelete = draftWithUpload
                            }
                        )
                    }
                }
            }
        }

        draftToDelete?.let { draftWithUpload ->
            AlertDialog(
                onDismissRequest = { draftToDelete = null },
                title = { Text("Delete Draft") },
                text = { Text("Are you sure you want to delete this draft? This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteDraft(draftWithUpload)
                            draftToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { draftToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun PublishingItem(
    draftWithUpload: DraftWithUpload,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val draft = draftWithUpload.draft
    val task = draftWithUpload.uploadTask
    
    val syncStatus = task?.status ?: draft.status.publication
    val progress = when (syncStatus) {
        is com.saurabh.artifact.model.SyncStatus.Uploading -> {
            if (task != null && task.totalBytes > 0) {
                task.uploadedBytes.toFloat() / task.totalBytes
            } else syncStatus.progress
        }
        else -> 0f
    }
    val isFailed = syncStatus is com.saurabh.artifact.model.SyncStatus.Failed
    val isWaiting = syncStatus is com.saurabh.artifact.model.SyncStatus.WaitingForNetwork || 
                   syncStatus is com.saurabh.artifact.model.SyncStatus.Queued
    val isFinalizing = syncStatus is com.saurabh.artifact.model.SyncStatus.Finalizing

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = draft.title?.ifBlank { "Publishing..." } ?: "Publishing...",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        
                        if (syncStatus is com.saurabh.artifact.model.SyncStatus.Uploading) {
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Text(
                        text = when {
                            isFailed -> "Upload failed"
                            syncStatus is com.saurabh.artifact.model.SyncStatus.WaitingForNetwork -> "Waiting for connection... 📡"
                            isFinalizing -> "Finalizing reflection..."
                            syncStatus is com.saurabh.artifact.model.SyncStatus.Uploading -> "Releasing your voice..."
                            else -> "Queued for upload..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isFailed -> MaterialTheme.colorScheme.error
                            syncStatus is com.saurabh.artifact.model.SyncStatus.WaitingForNetwork -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                if (isFailed && !isWaiting) {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, "Retry", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, "Cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(12.dp))

            if (isFailed && !isWaiting) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                    color = MaterialTheme.colorScheme.error,
                    trackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                )
            } else if (isFinalizing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                )
            }
        }
    }
}

@Composable
fun DraftItem(
    draftWithUpload: DraftWithUpload,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val draft = draftWithUpload.draft
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val date = SimpleDateFormat("MMM dd, yyyy HH:mm", locale).format(Date(draft.createdAt))
    
    var showMenu by remember { mutableStateOf(false) }

    val isProcessing = draft.status.processing is com.saurabh.artifact.model.ProcessingStatus.Active || 
        draft.draftState in listOf(
            ArtifactDraftState.SAVING,
            ArtifactDraftState.TRANSCODING,
            ArtifactDraftState.PROCESSING,
            ArtifactDraftState.NORMALIZING,
            ArtifactDraftState.WAVEFORM_GENERATION,
            ArtifactDraftState.TRANSCRIBING,
            ArtifactDraftState.SAFETY_CHECK
        )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Box {
            if (isProcessing) {
                ShimmerBackground()
            }

            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = draft.title?.ifBlank { "Untitled Reflection" } ?: "Untitled Reflection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (draft.status.publication is com.saurabh.artifact.model.SyncStatus.Recovering || 
                            draft.syncState == com.saurabh.artifact.model.SyncState.RECOVERING) {
                            "Recovering reflection • $date"
                        } else date,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (draft.status.publication is com.saurabh.artifact.model.SyncStatus.Recovering || 
                            draft.syncState == com.saurabh.artifact.model.SyncState.RECOVERING) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        }
                    )
                    
                    StatusBadge(draft)
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Continue Review") },
                            onClick = {
                                showMenu = false
                                onClick()
                            },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit Draft") },
                            onClick = {
                                showMenu = false
                                onEdit()
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Draft") },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ShimmerBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    )
}

@Composable
fun StatusBadge(draft: ArtifactDraftEntity) {
    val state = draft.status.processing
    val draftState = draft.draftState
    val syncStatus = draft.status.publication
    val syncState = draft.syncState

    val isProcessing = state is com.saurabh.artifact.model.ProcessingStatus.Active || 
        draftState in listOf(
            ArtifactDraftState.SAVING,
            ArtifactDraftState.TRANSCODING,
            ArtifactDraftState.PROCESSING,
            ArtifactDraftState.NORMALIZING,
            ArtifactDraftState.WAVEFORM_GENERATION,
            ArtifactDraftState.TRANSCRIBING,
            ArtifactDraftState.SAFETY_CHECK
        )

    val isInterrupted = syncStatus is com.saurabh.artifact.model.SyncStatus.Recovering || 
        syncState == com.saurabh.artifact.model.SyncState.RECOVERING

    val color = when {
        isInterrupted -> MaterialTheme.colorScheme.primary
        isProcessing -> MaterialTheme.colorScheme.secondary
        state is com.saurabh.artifact.model.ProcessingStatus.Failed || draftState == ArtifactDraftState.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    val text = when {
        isInterrupted -> "🌙 Held Safely"
        state is com.saurabh.artifact.model.ProcessingStatus.Active -> {
            when (state.stage) {
                com.saurabh.artifact.model.ProcessingStage.TRANSCODING -> "✨ Preparing audio..."
                com.saurabh.artifact.model.ProcessingStage.SAVING -> "✨ Saving..."
                com.saurabh.artifact.model.ProcessingStage.NORMALIZING -> "✨ Normalizing audio..."
                com.saurabh.artifact.model.ProcessingStage.TRANSCRIBING -> "✨ Transcribing using AI..."
                com.saurabh.artifact.model.ProcessingStage.WAVEFORM_GENERATION -> "✨ Generating waveform..."
                com.saurabh.artifact.model.ProcessingStage.SAFETY_CHECK -> "✨ Safety check..."
                com.saurabh.artifact.model.ProcessingStage.PRIVACY_SCANNING -> "✨ Privacy scan..."
                else -> "✨ Enhancing..."
            }
        }
        draftState == ArtifactDraftState.TRANSCODING -> "✨ Preparing audio..."
        draftState == ArtifactDraftState.SAVING -> "✨ Saving..."
        draftState == ArtifactDraftState.NORMALIZING -> "✨ Normalizing audio..."
        draftState == ArtifactDraftState.TRANSCRIBING -> "✨ Transcribing using AI..."
        draftState == ArtifactDraftState.WAVEFORM_GENERATION -> "✨ Generating waveform..."
        draftState == ArtifactDraftState.SAFETY_CHECK -> "✨ Safety check..."
        draftState == ArtifactDraftState.PROCESSING -> "✨ Enhancing..."
        else -> if (state is com.saurabh.artifact.model.ProcessingStatus.Failed) "❌ Error" 
                else draftState.name.replace("_", " ").lowercase().capitalize(java.util.Locale.ROOT)
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        contentColor = color,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isProcessing) FontWeight.Medium else FontWeight.Normal
        )
    }
}
