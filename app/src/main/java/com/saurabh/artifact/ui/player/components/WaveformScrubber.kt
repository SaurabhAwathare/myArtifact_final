package com.saurabh.artifact.ui.player.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.components.AmbientWaveform
import com.saurabh.artifact.ui.components.WaveformContext

import com.saurabh.artifact.ui.theme.EmberGlow

/**
 * WaveformScrubber - A cinematic, interactive waveform for seeking.
 */
@Composable
fun WaveformScrubber(
    amplitudes: List<Float>,
    progress: Float,
    isPaused: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(progress) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newProgress = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(newProgress)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        onSeek(dragProgress)
                    },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, _ ->
                        dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                        change.consume()
                    }
                )
            }
            .drawWithContent {
                drawContent()
                
                // Draw Playhead
                val currentProgress = if (isDragging) dragProgress else progress
                val playheadX = currentProgress * size.width
                
                drawRect(
                    color = Color.White,
                    topLeft = Offset(playheadX - 1.dp.toPx(), 0f),
                    size = Size(2.dp.toPx(), size.height)
                )
                
                drawCircle(
                    color = EmberGlow,
                    radius = 6.dp.toPx(),
                    center = Offset(playheadX, size.height / 2f)
                )
            }
    ) {
        AmbientWaveform(
            amplitudes = amplitudes,
            progress = if (isDragging) dragProgress else progress,
            isPaused = isPaused,
            isStatic = isDragging, // Disable animations during scrub for precision
            context = WaveformContext.Player,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
