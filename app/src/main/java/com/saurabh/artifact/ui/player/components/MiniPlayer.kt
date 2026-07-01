package com.saurabh.artifact.ui.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.components.ArtifactAvatar
import com.saurabh.artifact.ui.player.PlayerUiState
import com.saurabh.artifact.ui.theme.EmberGlow

@Composable
fun MiniPlayer(
    uiState: PlayerUiState,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val artifact = uiState.currentArtifact ?: return
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable { onExpand() },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val avatarConfig = artifact.author.avatarConfig.copy(
                seed = artifact.author.avatarConfig.seed.ifEmpty { artifact.id }
            )

            ArtifactAvatar(
                config = avatarConfig,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.05f))
            )
            
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = artifact.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                if (uiState.sleepTimerMillisRemaining != null) {
                    val minutes = (uiState.sleepTimerMillisRemaining / 60000).toInt()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = EmberGlow.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${minutes}m",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmberGlow.copy(alpha = 0.7f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                com.saurabh.artifact.ui.components.AmbientWaveform(
                    amplitudes = artifact.amplitudeData.takeIf { it.isNotEmpty() } ?: listOf(0.4f, 0.6f, 0.3f, 0.8f, 0.5f, 0.7f, 0.4f, 0.6f),
                    progress = uiState.playbackProgress,
                    modifier = Modifier.height(16.dp).fillMaxWidth(),
                    isPaused = !uiState.isPlaying,
                    context = com.saurabh.artifact.ui.components.WaveformContext.Mini
                )
            }
            
            IconButton(
                onClick = onTogglePlay,
                colors = IconButtonDefaults.iconButtonColors(contentColor = EmberGlow)
            ) {
                if (uiState.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = EmberGlow
                    )
                } else {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
