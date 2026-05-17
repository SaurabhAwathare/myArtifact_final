package com.saurabh.artifact.ui.components.state

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A subtle, pulsing placeholder to use during loading states.
 * Designed to be used in lists or cards as a "shimmer-lite" alternative.
 */
@Composable
fun LoadingPlaceholder(
    modifier: Modifier = Modifier,
    height: Dp = 20.dp,
    width: Modifier = Modifier.fillMaxWidth(),
    shape: CornerBasedShape = MaterialTheme.shapes.small,
    pulseColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = modifier
            .then(width)
            .height(height)
            .clip(shape)
            .background(pulseColor.copy(alpha = alpha))
    )
}
