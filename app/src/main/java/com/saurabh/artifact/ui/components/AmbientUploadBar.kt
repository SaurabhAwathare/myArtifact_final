package com.saurabh.artifact.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
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
import com.saurabh.artifact.model.PublishState
import com.saurabh.artifact.ui.theme.EmberGlow
import com.saurabh.artifact.ui.theme.Obsidian900

/**
 * A persistent ambient component that shows the progress of background uploads.
 * Redesigned to remove the dismiss button and show granular status.
 */
@Composable
fun AmbientUploadBar(
    state: PublishState?,
    modifier: Modifier = Modifier,
    onRetry: (String) -> Unit = {},
    onCancel: (String) -> Unit = {},
) {
    AnimatedVisibility(
        visible = state != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        if (state == null) return@AnimatedVisibility

        val isError = state is PublishState.Error

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = if (isError) {
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
                    UploadStatusIcon(state = state)
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                Color.White
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = state.getDisplayText(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isError) {
                                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                            } else {
                                Color.White.copy(alpha = 0.6f)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (state is PublishState.Uploading) {
                        Text(
                            text = "${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = EmberGlow.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }

                    if (isError) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { onCancel(state.draftId) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Cancel", style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(4.dp))
                            Button(
                                onClick = { onRetry(state.draftId) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Retry", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                val progress = when(state) {
                    is PublishState.Uploading -> state.progress
                    is PublishState.Finalizing -> 0.95f
                    is PublishState.Published -> 1f
                    is PublishState.Preparing -> 0.05f
                    else -> 0f
                }

                AmberProgressLine(
                    progress = progress,
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
private fun UploadStatusIcon(state: PublishState) {
    val icon = when (state) {
        is PublishState.Published -> Icons.Rounded.CheckCircle
        is PublishState.Error -> Icons.Rounded.ErrorOutline
        else -> Icons.Rounded.CloudUpload
    }
    
    val tint = when (state) {
        is PublishState.Published -> Color(0xFF4CAF50)
        is PublishState.Error -> MaterialTheme.colorScheme.error
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

private fun PublishState.getDisplayText(): String = when (this) {
    is PublishState.Preparing -> displayStatus
    is PublishState.Uploading -> if (isWaitingForNetwork) "Waiting for network..." else "Releasing your reflection..."
    is PublishState.Finalizing -> "Securing your essence..."
    is PublishState.Published -> "Shared gently."
    is PublishState.Error -> message
    is PublishState.Idle -> ""
}
