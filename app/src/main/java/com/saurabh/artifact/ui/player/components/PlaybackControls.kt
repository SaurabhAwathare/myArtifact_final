package com.saurabh.artifact.ui.player.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Replay10
import com.saurabh.artifact.ui.theme.EmberGlow

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val infiniteTransition = rememberInfiniteTransition(label = "PlayButtonPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onRewind()
        }) {
            Icon(
                Icons.Rounded.Replay10,
                contentDescription = "Rewind 10s",
                modifier = Modifier.size(32.dp),
                tint = Color.White.copy(alpha = 0.6f)
            )
        }

        Spacer(modifier = Modifier.width(40.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(96.dp)
        ) {
            // Breathing Ripple
            if (isPlaying) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = CircleShape,
                    color = EmberGlow.copy(alpha = pulseAlpha)
                ) {}
            }

            Surface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.08f),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onTogglePlay()
                },
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(40.dp),
                        tint = if (isPlaying) Color.White else EmberGlow
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(40.dp))

        IconButton(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onForward()
        }) {
            Icon(
                Icons.Rounded.Forward10,
                contentDescription = "Forward 10s",
                modifier = Modifier.size(32.dp),
                tint = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun PlaybackSpeedSelector(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val speeds = listOf(0.8f, 1.0f, 1.2f, 1.5f)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        speeds.forEachIndexed { index, speed ->
            val isSelected = speed == currentSpeed
            
            TextButton(
                onClick = { onSpeedSelected(speed) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (isSelected) EmberGlow else Color.White.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = "${speed}x",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }

            if (index < speeds.size - 1) {
                Text(
                    text = "•",
                    color = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}
