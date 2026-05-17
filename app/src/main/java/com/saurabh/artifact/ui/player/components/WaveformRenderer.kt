package com.saurabh.artifact.ui.player.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun AmbientWaveform(
    progress: Float,
    waveformData: List<Float>,
    modifier: Modifier = Modifier,
    activeColor: Color = Color(0xFFFFB74D), // Warm Amber
    inactiveColor: Color = Color.White.copy(alpha = 0.2f),
    barWidth: Float = 4f,
    gap: Float = 2f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Canvas(modifier = modifier.fillMaxWidth().height(100.dp)) {
        val width = size.width
        val height = size.height
        val centerY = height / 2
        
        // If no data, show a flat line
        val data = if (waveformData.isEmpty()) List(50) { 0.1f } else waveformData
        
        val totalBars = data.size
        val barTotalWidth = barWidth + gap
        val startOffset = (width - (totalBars * barTotalWidth)) / 2

        data.forEachIndexed { index, amplitude ->
            val x = startOffset + (index * barTotalWidth)
            val barHeight = amplitude * height * pulseScale
            val isPlayed = index.toFloat() / totalBars <= progress

            val color = if (isPlayed) activeColor else inactiveColor
            
            drawRoundRect(
                color = color,
                topLeft = Offset(x, centerY - (barHeight / 2)),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2)
            )
        }
    }
}
