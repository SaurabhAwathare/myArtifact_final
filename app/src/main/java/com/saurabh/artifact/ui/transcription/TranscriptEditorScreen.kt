package com.saurabh.artifact.ui.transcription

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.EditAction
import com.saurabh.artifact.model.TranscriptSegment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptEditorScreen(
    viewModel: TranscriptionViewModel,
    onBack: () -> Unit,
    onPublish: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Story", color = Color(0xFFF2E7D5)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFFF2E7D5))
                    }
                },
                actions = {
                    if (uiState.editHistory.canUndo) {
                        IconButton(onClick = { viewModel.undo() }) {
                            Icon(Icons.Default.Undo, contentDescription = "Undo", tint = Color(0xFFFFB300))
                        }
                    }
                    if (uiState.editHistory.canRedo) {
                        IconButton(onClick = { viewModel.redo() }) {
                            Icon(Icons.Default.Redo, contentDescription = "Redo", tint = Color(0xFFFFB300))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D0D0D))
            )
        },
        bottomBar = {
            EditorBottomBar(
                isPlaying = uiState.isPlaying,
                onTogglePlayback = { viewModel.togglePlayback() },
                onPublish = onPublish
            )
        },
        containerColor = Color(0xFF0D0D0D)
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column {
                ProcessingOverlay(uiState.isProcessing, uiState.processingMessage)
                
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 24.dp)
                ) {
                    items(uiState.transcript) { segment ->
                        val isSelected = uiState.selectedSegmentIds.contains(segment.id)
                        val activeOps = uiState.editHistory.activeOperations
                        val segmentAction = activeOps.find { it.segmentIds.contains(segment.id) }?.action
                        
                        EditableSegmentItem(
                            segment = segment,
                            isSelected = isSelected,
                            action = segmentAction,
                            onToggleSelection = { viewModel.toggleSegmentSelection(segment.id) }
                        )
                    }
                }
            }
            
            // Contextual Toolbar
            AnimatedVisibility(
                visible = uiState.selectedSegmentIds.isNotEmpty(),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)
            ) {
                ContextualEditToolbar(
                    onAction = { viewModel.applySemanticEdit(it) }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EditableSegmentItem(
    segment: TranscriptSegment,
    isSelected: Boolean,
    action: EditAction?,
    onToggleSelection: () -> Unit
) {
    val backgroundColor = when {
        isSelected -> Color(0xFFFFB300).copy(alpha = 0.15f)
        action == EditAction.REMOVE -> Color(0xFFE57373).copy(alpha = 0.05f)
        else -> Color.Transparent
    }
    
    val borderColor = when {
        isSelected -> Color(0xFFFFB300)
        action == EditAction.REMOVE -> Color(0xFFE57373).copy(alpha = 0.5f)
        else -> Color.Transparent
    }

    val textDecoration = if (action == EditAction.REMOVE) TextDecoration.LineThrough else null
    val textColor = when (action) {
        EditAction.REMOVE -> Color(0xFFE57373).copy(alpha = 0.6f)
        EditAction.MUTE, EditAction.REDACT -> Color(0xFFBDBDBD).copy(alpha = 0.6f)
        else -> Color(0xFFF2E7D5)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onToggleSelection,
                onLongClick = onToggleSelection
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTime(segment.startMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFBDBDBD)
            )
            Spacer(modifier = Modifier.weight(1f))
            if (action != null) {
                Icon(
                    imageVector = when(action) {
                        EditAction.REMOVE -> Icons.Default.Delete
                        EditAction.MUTE -> Icons.Default.VolumeOff
                        EditAction.REDACT -> Icons.Default.Lock
                    },
                    contentDescription = null,
                    tint = if (action == EditAction.REMOVE) Color(0xFFE57373) else Color(0xFFFFB300),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = segment.text,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
            textDecoration = textDecoration
        )
    }
}

@Composable
fun ContextualEditToolbar(onAction: (EditAction) -> Unit) {
    Surface(
        color = Color(0xFF1A1A1A),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 8.dp,
        modifier = Modifier.border(1.dp, Color(0xFF333333), RoundedCornerShape(28.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionButton(Icons.Default.Delete, "Remove", Color(0xFFE57373)) { onAction(EditAction.REMOVE) }
            VerticalDivider(modifier = Modifier.height(24.dp).width(1.dp), color = Color(0xFF333333))
            ActionButton(Icons.Default.VolumeOff, "Mute", Color(0xFFFFB300)) { onAction(EditAction.MUTE) }
            VerticalDivider(modifier = Modifier.height(24.dp).width(1.dp), color = Color(0xFF333333))
            ActionButton(Icons.Default.Lock, "Redact", Color(0xFFFFB300)) { onAction(EditAction.REDACT) }
        }
    }
}

@Composable
fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = androidx.compose.ui.Modifier.combinedClickable(onClick = onClick)
    ) {
        Icon(icon, contentDescription = label, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun ProcessingOverlay(visible: Boolean, message: String) {
    AnimatedVisibility(visible = visible) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Color(0xFFFFB300),
            trackColor = Color.Transparent
        )
        if (message.isNotEmpty()) {
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFB300),
                modifier = Modifier.padding(start = 20.dp, top = 4.dp)
            )
        }
    }
}

@Composable
fun EditorBottomBar(isPlaying: Boolean, onTogglePlayback: () -> Unit, onPublish: () -> Unit) {
    Surface(
        color = Color(0xFF1A1A1A),
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onTogglePlayback,
                modifier = Modifier.size(56.dp).background(Color(0xFFFFB300), RoundedCornerShape(28.dp))
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Playback",
                    tint = Color.Black
                )
            }
            
            Button(
                onClick = onPublish,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300)),
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text("Confirm & Publish", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
