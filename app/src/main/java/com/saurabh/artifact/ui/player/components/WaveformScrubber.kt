package com.saurabh.artifact.ui.player.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
    ) {
        AmbientWaveform(
            amplitudes = amplitudes,
            progress = if (isDragging) dragProgress else progress,
            isPaused = isPaused,
            context = WaveformContext.Player,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
