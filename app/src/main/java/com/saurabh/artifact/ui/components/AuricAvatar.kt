package com.saurabh.artifact.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * AuricAvatar - A production-grade generative abstract identity.
 * Replaces cartoon avatars with "emotional auras" derived from a stable seed.
 * Designed for anonymity, psychological safety, and premium emotional depth.
 */
@Composable
fun AuricAvatar(
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    isStatic: Boolean = false
) {
    // Deterministic palette generation from seed
    val palette = remember(seed) {
        generateAuraPalette(seed)
    }

    // Subtle breathing animation for presence
    val infiniteTransition = rememberInfiniteTransition(label = "aura_presence")
    val breathScale by if (isStatic) {
        remember { mutableFloatStateOf(1f) }
    } else {
        infiniteTransition.animateFloat(
            initialValue = 0.98f,
            targetValue = 1.02f,
            animationSpec = infiniteRepeatable(
                animation = tween(5000, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "aura_breath"
        )
    }

    val rotation by if (isStatic) {
        remember { mutableFloatStateOf(0f) }
    } else {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(20000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "aura_rotation"
        )
    }

    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            // 1. Base Layer: Soft Ambient Glow
            drawAuraLayer(
                colors = palette.take(2),
                radiusMult = 0.5f * breathScale,
                alpha = 0.4f
            )

            // 2. Core Identity Layer: Multi-color Gradient
            drawAuraLayer(
                colors = palette,
                radiusMult = 0.35f,
                alpha = 0.8f,
                rotation = rotation
            )

            // 3. Highlight Layer: Center Brilliance
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.3f),
                    0.6f to Color.Transparent,
                    center = center,
                    radius = size.toPx() * 0.25f
                ),
                radius = size.toPx() * 0.25f
            )
        }
    }
}

private fun DrawScope.drawAuraLayer(
    colors: List<Color>,
    radiusMult: Float,
    alpha: Float,
    rotation: Float = 0f
) {
    val radius = size.maxDimension * radiusMult
    
    // We can't rotate the DrawScope directly for a brush, 
    // but the brush itself is independent of the rotation of the shape if we center it.
    // However, for a linear gradient, the angle matters. 
    // Since we want abstract shifting, we'll use a linear gradient for the core.
    
    drawCircle(
        brush = Brush.linearGradient(
            colors = colors.map { it.copy(alpha = it.alpha * alpha) }
        ),
        radius = radius
    )
}

/**
 * Generates a stable, harmonious color palette from any string seed.
 */
private fun generateAuraPalette(seed: String): List<Color> {
    val hash = seed.hashCode()
    val random = kotlin.random.Random(hash)
    
    // Choose a base hue
    val baseHue = abs(hash % 360).toFloat()
    
    // Generate 3 analogous or complementary colors
    return listOf(
        hslToColor(baseHue, 0.6f, 0.7f), // Primary
        hslToColor((baseHue + 30) % 360, 0.5f, 0.6f), // Analogous 1
        hslToColor((baseHue + 60) % 360, 0.4f, 0.5f)  // Analogous 2
    ).shuffled(random)
}

/**
 * Helper to convert HSL to Compose Color
 */
private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    
    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0f)
        h < 120 -> Triple(x, c, 0f)
        h < 180 -> Triple(0f, c, x)
        h < 240 -> Triple(0f, x, c)
        h < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    
    return Color(
        red = (r + m),
        green = (g + m),
        blue = (b + m),
        alpha = 1f
    )
}
