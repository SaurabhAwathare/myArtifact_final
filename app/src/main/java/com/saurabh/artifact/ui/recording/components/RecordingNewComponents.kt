package com.saurabh.artifact.ui.recording.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.theme.GoldAura500

@Composable
fun NewRecordingWaveform(
    amplitude: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedAmplitude by animateFloatAsState(
        targetValue = if (isRecording) amplitude else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "Amplitude"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        // Glow Background
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(80.dp)
                .blur(40.dp)
                .alpha(0.15f * animatedAmplitude)
                .background(
                    Brush.radialGradient(
                        colors = listOf(GoldAura500, Color.Transparent)
                    )
                )
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val barCount = 40
            val barWidth = 3.dp.toPx()
            val spacing = 6.dp.toPx()
            val centerY = size.height / 2
            val centerX = size.width / 2

            for (i in 0 until barCount) {
                val distanceFromCenter = kotlin.math.abs(i - barCount / 2f) / (barCount / 2f)
                val scale = (1f - distanceFromCenter).coerceIn(0.1f, 1f)
                
                // Mirror effect
                val xOffset = (i - barCount / 2) * spacing
                
                // Add some randomness/noise to the amplitude for organic feel
                val noise = (kotlin.math.sin(i.toFloat() * 0.5f) * 0.2f) + 0.8f
                val height = (animatedAmplitude * size.height * scale * noise).coerceAtLeast(4.dp.toPx())

                drawLine(
                    color = GoldAura400.copy(alpha = (1f - distanceFromCenter).coerceIn(0.2f, 1f)),
                    start = Offset(centerX + xOffset, centerY - height / 2),
                    end = Offset(centerX + xOffset, centerY + height / 2),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
