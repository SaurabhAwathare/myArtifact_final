package com.saurabh.artifact.ui.drafts.edit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.ui.components.EmotionSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftEditScreen(
    draftId: String,
    onBack: () -> Unit,
    onReview: () -> Unit,
    onPublish: () -> Unit,
    viewModel: DraftEditViewModel = hiltViewModel(),
) {
    val title by viewModel.title.collectAsState()
    val selectedEmotion by viewModel.emotion.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(draftId) {
        viewModel.loadDraft(draftId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Draft") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.saveChanges()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
            // Audio Playback Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.togglePlayback() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play"
                        )
                    }
                    
                    Spacer(Modifier.width(16.dp))
                    
                    Column {
                        Text(
                            "Listen to Recording",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (isPlaying) "Playing..." else "Paused",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Title Editing
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Title",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { viewModel.updateTitle(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Give your artifact a name...") },
                    singleLine = true
                )
            }

            // Emotion Selection
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmotionSelector(
                    selectedEmotion = selectedEmotion?.label ?: "",
                    onSelect = { label ->
                        val emotion = com.saurabh.artifact.model.Emotion.entries.find { it.label == label }
                        viewModel.updateEmotion(emotion)
                    }
                )
            }

            Spacer(Modifier.weight(1f))

            // Primary Actions
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        viewModel.saveChanges()
                        onReview()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Continue to Review")
                }
                
                OutlinedButton(
                    onClick = {
                        viewModel.saveChanges()
                        onPublish()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Publish Directly")
                }
            }
        }
    }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Draft") },
            text = { Text("Are you sure you want to delete this draft? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDraft {
                            showDeleteDialog = false
                            onBack()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
