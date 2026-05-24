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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftListScreen(
    onBack: () -> Unit,
    onReviewDraft: (String) -> Unit,
    viewModel: DraftListViewModel = hiltViewModel()
) {
    val drafts by viewModel.drafts.collectAsState()

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
        if (drafts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No drafts found", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(drafts, key = { it.id }) { draft ->
                    DraftItem(
                        draft = draft,
                        onClick = { 
                            if (draft.syncState == com.saurabh.artifact.model.SyncState.INTERRUPTED) {
                                // Trigger processing for interrupted draft
                                viewModel.playDraft(draft) // This now triggers processing too
                            } else {
                                onReviewDraft(draft.id)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DraftItem(
    draft: ArtifactDraftEntity,
    onClick: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val date = SimpleDateFormat("MMM dd, yyyy HH:mm", locale).format(Date(draft.createdAt))
    
    val isProcessing = draft.draftState in listOf(
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
                        text = if (draft.syncState == com.saurabh.artifact.model.SyncState.INTERRUPTED) {
                            "Interrupted reflection • $date"
                        } else date,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (draft.syncState == com.saurabh.artifact.model.SyncState.INTERRUPTED) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        }
                    )
                    
                    StatusBadge(draft)
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
    val state = draft.draftState
    val syncState = draft.syncState

    val isProcessing = state in listOf(
        ArtifactDraftState.SAVING,
        ArtifactDraftState.TRANSCODING,
        ArtifactDraftState.PROCESSING,
        ArtifactDraftState.NORMALIZING,
        ArtifactDraftState.WAVEFORM_GENERATION,
        ArtifactDraftState.TRANSCRIBING,
        ArtifactDraftState.SAFETY_CHECK
    )

    val isInterrupted = syncState == com.saurabh.artifact.model.SyncState.INTERRUPTED

    val color = when {
        isInterrupted -> MaterialTheme.colorScheme.primary
        isProcessing -> MaterialTheme.colorScheme.secondary
        state == ArtifactDraftState.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    val text = when {
        isInterrupted -> "🌙 Held Safely"
        state == ArtifactDraftState.TRANSCODING -> "✨ Preparing audio..."
        state == ArtifactDraftState.SAVING -> "✨ Saving..."
        state == ArtifactDraftState.NORMALIZING -> "✨ Normalizing audio..."
        state == ArtifactDraftState.TRANSCRIBING -> "✨ Transcribing using AI..."
        state == ArtifactDraftState.WAVEFORM_GENERATION -> "✨ Generating waveform..."
        state == ArtifactDraftState.SAFETY_CHECK -> "✨ Safety check..."
        state == ArtifactDraftState.PROCESSING -> "✨ Enhancing..."
        else -> state.name.replace("_", " ").lowercase().capitalize(Locale.ROOT)
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
