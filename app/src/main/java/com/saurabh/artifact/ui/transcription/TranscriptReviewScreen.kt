package com.saurabh.artifact.ui.transcription

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.ui.transcription.components.PublishConfirmationDialog
import com.saurabh.artifact.ui.transcription.components.SensitiveInfoBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptReviewScreen(
    viewModel: TranscriptReviewViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.showReflectionPrompt) {
        PublishConfirmationDialog(
            onConfirm = { viewModel.confirmFinalPublish() },
            onDismiss = { viewModel.dismissReflectionPrompt() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review Reflection", color = Color(0xFFF2E7D5)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFFF2E7D5))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D0D0D))
            )
        },
        bottomBar = {
            ReviewBottomBar(
                onPlay = { viewModel.togglePlayback() },
                onPublish = { viewModel.onPublishClick() },
                isPlaying = uiState.isPlaying
            )
        },
        containerColor = Color(0xFF0D0D0D)
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            SensitiveInfoBanner(visible = uiState.sensitiveSegments.isNotEmpty())
            
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 24.dp)
            ) {
                items(uiState.transcript) { segment ->
                    TranscriptSegmentItem(
                        segment = segment,
                        isCurrent = (segment.startMs <= uiState.currentAudioPositionMs) && (segment.endMs >= uiState.currentAudioPositionMs),
                        isSensitive = uiState.sensitiveSegments.contains(segment.id),
                        onEdit = { id, newText -> viewModel.updateSegmentText(id, newText) }
                    )
                }
            }
        }
    }
}

@Composable
fun TranscriptSegmentItem(
    segment: TranscriptSegment,
    isCurrent: Boolean,
    isSensitive: Boolean,
    onEdit: (String, String) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(segment.text) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) Color(0xFF1A1A1A) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (isSensitive) Color(0xFFE57373) else if (isCurrent) Color(0xFFFFB300) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTime(segment.startMs),
                style = MaterialTheme.typography.labelSmall,
                color = if (isCurrent) Color(0xFFFFB300) else Color(0xFFBDBDBD)
            )
            if (isSensitive) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Sensitive",
                    tint = Color(0xFFE57373),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        if (isEditing) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    focusedTextColor = Color(0xFFF2E7D5),
                    unfocusedTextColor = Color(0xFFBDBDBD)
                )
            )
            TextButton(onClick = { 
                onEdit(segment.id, text)
                isEditing = false 
            }) {
                Text("Save", color = Color(0xFFFFB300))
            }
        } else {
            Text(
                text = segment.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) Color(0xFFF2E7D5) else Color(0xFFBDBDBD),
                modifier = Modifier.clickable { isEditing = true }
            )
        }
    }
}

@Composable
fun ReviewBottomBar(onPlay: () -> Unit, onPublish: () -> Unit, isPlaying: Boolean) {
    Surface(
        color = Color(0xFF1A1A1A),
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPlay,
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0xFFFFB300), RoundedCornerShape(28.dp))
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Warning else Icons.Default.PlayArrow,
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

fun formatTime(ms: Long): String {
    val sec = (ms / 1000) % 60
    val min = (ms / (1000 * 60)) % 60
    return "%d:%02d".format(min, sec)
}
