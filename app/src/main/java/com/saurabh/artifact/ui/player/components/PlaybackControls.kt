package com.saurabh.artifact.ui.player.components

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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
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
                Icons.Rounded.Replay,
                contentDescription = "Rewind 15s",
                modifier = Modifier.size(36.dp),
                tint = Color.White.copy(alpha = 0.8f)
            )
        }

        Spacer(modifier = Modifier.width(32.dp))

        Surface(
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.1f),
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
                    modifier = Modifier.size(44.dp),
                    tint = EmberGlow
                )
            }
        }

        Spacer(modifier = Modifier.width(32.dp))

        IconButton(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onForward()
        }) {
            Icon(
                Icons.Rounded.Forward10,
                contentDescription = "Forward 15s",
                modifier = Modifier.size(36.dp),
                tint = Color.White.copy(alpha = 0.8f)
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
