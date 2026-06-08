package com.saurabh.artifact.ui.recording.components

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.util.TimeUtils

@Composable
fun MiniRecorder(
    status: RecordingStatus,
    durationSeconds: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (status != RecordingStatus.RECORDING && status != RecordingStatus.PAUSED) {
        return
    }

    Surface(
        modifier = modifier
            .padding(16.dp)
            .height(64.dp)
            .fillMaxWidth(0.9f)
            .clip(RoundedCornerShape(32.dp))
            .clickable(onClick = onClick),
        color = Color(0xFF1A0808).copy(alpha = 0.98f),
        tonalElevation = 12.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Animated Red Dot
            val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
            val dotScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "DotScale"
            )

            Box(
                modifier = Modifier
                    .size(10.dp)
                    .scale(dotScale)
                    .background(Color(0xFFE91E63), CircleShape)
            )

            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = if (status == RecordingStatus.PAUSED) "Recording Paused" else "Recording in progress",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = TimeUtils.formatDuration(durationSeconds, LocalConfiguration.current),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        letterSpacing = 1.sp
                    ),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "RETURN",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFFE91E63),
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// DELETE: private fun formatDuration(seconds: Long): String {
//    val mins = seconds / 60
//    val secs = seconds % 60
//    return String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
// }
