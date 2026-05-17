package com.saurabh.artifact.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.components.AmbientWaveform
import com.saurabh.artifact.ui.components.WaveformContext

/**
 * BottomPlayer - The floating Aura.
 * Ambient playback presence that follows the user quietly like a candle.
 */
@Composable
fun BottomPlayer(
    title: String,
    isPlaying: Boolean,
    progress: Float,
    onTogglePlayback: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ArtifactTheme.colors
    
    Surface(
        onClick = onClick,
        modifier = modifier
            .padding(16.dp)
            .height(80.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp), // Less standard rounding
        color = colors.surfaceHearth.copy(alpha = 0.85f),
        tonalElevation = 0.dp // Disabling Material3 tonal elevation for custom depth
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Ambient Golden Diffusion
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(24.dp)
                    .background(colors.surfaceGlow.copy(alpha = 0.08f))
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. The Aura Logo
                AuraLogo(
                    size = 44.dp,
                    isPulsing = isPlaying
                )

                Spacer(modifier = Modifier.width(16.dp))

                // 2. Artifact Info & Waveform
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurfaceMain,
                        maxLines = 1
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Mini waveform as progress indicator
                    AmbientWaveform(
                        amplitudes = listOf(0.4f, 0.6f, 0.5f, 0.8f, 0.3f, 0.7f, 0.5f, 0.4f),
                        progress = progress,
                        modifier = Modifier.height(20.dp),
                        isPaused = !isPlaying,
                        context = WaveformContext.Mini
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // 3. Play/Pause with Aura Glow
                IconButton(
                    onClick = onTogglePlayback,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = colors.onSurfaceAura
                    )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Toggle Playback",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
