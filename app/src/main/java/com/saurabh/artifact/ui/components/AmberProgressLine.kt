package com.saurabh.artifact.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.theme.EmberGlow

/**
 * A subtle, glowing amber line used to indicate background progress.
 */
@Composable
fun AmberProgressLine(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 1000, easing = LinearOutSlowInEasing),
        label = "AmberProgress"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "GlowPulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
    ) {
        val width = size.width
        val progressWidth = width * animatedProgress.coerceIn(0f, 1f)

        // Background track
        drawLine(
            color = Color.White.copy(alpha = 0.05f),
            start = Offset(0f, size.height / 2),
            end = Offset(width, size.height / 2),
            strokeWidth = size.height,
            cap = StrokeCap.Round
        )

        // Progress line with glow
        if (progressWidth > 0) {
            drawProgressWithGlow(progressWidth, glowAlpha)
        }
    }
}

private fun DrawScope.drawProgressWithGlow(progressWidth: Float, glowAlpha: Float) {
    // Outer soft glow
    drawLine(
        brush = Brush.horizontalGradient(
            colors = listOf(
                EmberGlow.copy(alpha = 0f),
                EmberGlow.copy(alpha = glowAlpha * 0.4f),
                EmberGlow.copy(alpha = 0f)
            ),
            startX = 0f,
            endX = progressWidth
        ),
        start = Offset(0f, size.height / 2),
        end = Offset(progressWidth, size.height / 2),
        strokeWidth = size.height * 4f,
        cap = StrokeCap.Round
    )

    // Inner bright core
    drawLine(
        color = EmberGlow,
        start = Offset(0f, size.height / 2),
        end = Offset(progressWidth, size.height / 2),
        strokeWidth = size.height,
        cap = StrokeCap.Round
    )
}
