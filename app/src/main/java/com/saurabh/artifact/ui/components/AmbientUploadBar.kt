package com.saurabh.artifact.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.AmbientUploadStatus
import com.saurabh.artifact.model.UploadSession
import com.saurabh.artifact.ui.theme.EmberGlow
import com.saurabh.artifact.ui.theme.Obsidian900

/**
 * A persistent ambient component that shows the progress of background uploads.
 * Positioned above the bottom navigation, matching the MiniPlayer aesthetic.
 */
@Composable
fun AmbientUploadBar(
    session: UploadSession?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = session != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        if (session == null) return@AnimatedVisibility

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = if (session.status is AmbientUploadStatus.Error) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
            } else {
                Obsidian900.copy(alpha = 0.95f)
            },
            tonalElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
        ) {
            Column(modifier = Modifier.padding(bottom = 2.dp)) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UploadStatusIcon(status = session.status)
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (session.status is AmbientUploadStatus.Error) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                Color.White
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = session.status.getDisplayText(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (session.status is AmbientUploadStatus.Error) {
                                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                            } else {
                                Color.White.copy(alpha = 0.6f)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (session.status is AmbientUploadStatus.UploadingAudio) {
                        Text(
                            text = "${(session.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = EmberGlow.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss",
                            tint = if (session.status is AmbientUploadStatus.Error) {
                                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f)
                            } else {
                                Color.White.copy(alpha = 0.3f)
                            },
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                AmberProgressLine(
                    progress = session.progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
                
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun UploadStatusIcon(status: AmbientUploadStatus) {
    val icon = when (status) {
        is AmbientUploadStatus.Completed -> Icons.Rounded.CheckCircle
        is AmbientUploadStatus.Error -> Icons.Rounded.ErrorOutline
        else -> Icons.Rounded.CloudUpload
    }
    
    val tint = when (status) {
        is AmbientUploadStatus.Completed -> Color(0xFF4CAF50)
        is AmbientUploadStatus.Error -> MaterialTheme.colorScheme.error
        else -> EmberGlow
    }

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}
