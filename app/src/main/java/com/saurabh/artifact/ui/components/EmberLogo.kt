package com.saurabh.artifact.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.components.motion.MotionTokens

/**
 * The Aura - A softly glowing, candlelit brand identity.
 * Represents "quiet golden light in darkness".
 */
@Composable
fun AuraLogo(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    isPulsing: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "AuraBreathing")
    val colors = ArtifactTheme.colors
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPulsing) 1.08f else 1f, // Reduced scale for "calmer" movement
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = MotionTokens.CalmEasing), // Slower breathing
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (isPulsing) 0.6f else 0.4f, // Softer glow intensities
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = MotionTokens.CalmEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Outer atmospheric diffusion (The Aura)
        Canvas(modifier = Modifier.size(size * 1.8f)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.surfaceGlow.copy(alpha = glowAlpha * 0.4f),
                        Color.Transparent
                    )
                ),
                radius = (size.toPx() * pulseScale * 0.9f)
            )
        }

        // The Hearth (The Core)
        Canvas(modifier = Modifier.size(size)) {
            val radius = (size.toPx() / 2) * pulseScale

            // Core Golden Light
            drawCircle(
                brush = Brush.radialGradient(
                    0f to colors.onSurfaceAura,
                    0.5f to colors.onSurfaceAura.copy(alpha = 0.7f),
                    1f to Color.Transparent
                ),
                radius = radius
            )

            // Inner "Flame" stroke - now a "wick" glow
            drawCircle(
                color = colors.onSurfaceMain.copy(alpha = 0.2f),
                radius = radius * 0.6f,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}

/** Legacy alias for smooth transition */
@Composable
fun EmberLogo(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    isPulsing: Boolean = true
) = AuraLogo(modifier, size, isPulsing)

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun AuraLogoPreview() {
    ArtifactTheme {
        Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            AuraLogo()
        }
    }
}
