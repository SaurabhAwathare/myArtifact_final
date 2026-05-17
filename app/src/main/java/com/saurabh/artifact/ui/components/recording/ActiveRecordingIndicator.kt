package com.saurabh.artifact.ui.components.recording

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.audio.RecordingService
import com.saurabh.artifact.data.local.RecordingStatus
import java.util.Locale

@Composable
fun ActiveRecordingIndicator(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recordingState by RecordingService.recordingState.collectAsState()
    
    val isActive = recordingState.status == RecordingStatus.RECORDING || 
                   recordingState.status == RecordingStatus.PAUSED ||
                   recordingState.status == RecordingStatus.PREPARING

    AnimatedVisibility(
        visible = isActive,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(CircleShape)
                .clickable { onClick() },
            color = Color(0xFFC0392B), // The warm red
            contentColor = Color.White,
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = when (recordingState.status) {
                            RecordingStatus.PAUSED -> "Recording Paused"
                            RecordingStatus.PREPARING -> "Preparing..."
                            else -> "Recording Reflection..."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                Text(
                    text = formatDuration(recordingState.durationSeconds),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFeatureSettings = "tnum"
                    ),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
}
