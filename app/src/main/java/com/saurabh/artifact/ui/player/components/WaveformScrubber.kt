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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
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
    modifier: Modifier = Modifier,
    onScrubbing: (Float) -> Unit = {},
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(progress) }
    val currentProgress = if (isDragging) dragProgress else progress

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = currentProgress,
                    range = 0f..1f,
                    steps = 0
                )
                contentDescription = "Audio playback position"
                stateDescription = "${(currentProgress * 100).toInt()}%"
                setProgress { targetProgress ->
                    val coerced = targetProgress.coerceIn(0f, 1f)
                    if (coerced != currentProgress) {
                        onSeek(coerced)
                        true
                    } else {
                        false
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newProgress = (offset.x / size.width).coerceIn(0f, 1f)
                    onScrubbing(newProgress)
                    onSeek(newProgress)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { 
                        isDragging = true 
                        dragProgress = progress
                    },
                    onDragEnd = {
                        isDragging = false
                        onSeek(dragProgress)
                    },
                    onDragCancel = { 
                        isDragging = false 
                        onScrubbing(progress) // Reset to actual position if needed, or just let it snap back
                        onSeek(progress)
                    },
                    onDrag = { change, _ ->
                        dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                        onScrubbing(dragProgress)
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
