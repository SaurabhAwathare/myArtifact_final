package com.saurabh.artifact.ui.avatar.renderer

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import com.saurabh.artifact.model.AvatarConfig
import kotlin.math.abs

class AuricRenderer : AvatarRenderer {

    @Composable
    override fun Render(
        config: AvatarConfig,
        animationState: AvatarAnimationState,
        modifier: Modifier
    ) {
        val palette = remember(config.seed) { generateAuraPalette(config.seed) }

        // Fix: Capture the latest animationState to prevent stale reads in derivedStateOf
        val currentAnimationState by rememberUpdatedState(animationState)

        // Optimization: Logic that depends on high-frequency state
        val isShimmering by remember {
            derivedStateOf { currentAnimationState.pulse() > 1.01f }
        }

        Canvas(
            modifier = modifier.drawWithCache {
                val centerOffset = size.center
                val minDim = size.minDimension
                val maxDim = size.maxDimension

                // Cache brushes to avoid per-frame allocations
                val auraColors = palette.take(2)
                val baseAuraBrush = Brush.linearGradient(colors = auraColors)
                val coreAuraBrush = Brush.linearGradient(colors = palette)
                
                val highlightRadius = minDim * 0.25f
                val highlightBrush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.3f),
                    0.6f to Color.Transparent,
                    center = centerOffset,
                    radius = highlightRadius
                )

                onDrawBehind {
                    // 1. Base Layer: Soft Ambient Glow (Animated Pulse)
                    val p = currentAnimationState.pulse()
                    val baseAlpha = if (isShimmering) 0.5f else 0.4f
                    
                    withTransform({
                        scale(p, p, centerOffset)
                    }) {
                        drawCircle(
                            brush = baseAuraBrush,
                            radius = maxDim * 0.5f,
                            alpha = baseAlpha
                        )
                    }

                    // 2. Core Identity Layer: Multi-color Gradient (Animated Rotation)
                    withTransform({
                        rotate(currentAnimationState.rotation(), centerOffset)
                    }) {
                        drawCircle(
                            brush = coreAuraBrush,
                            radius = maxDim * 0.35f,
                            alpha = 0.8f
                        )
                    }

                    // 3. Highlight Layer: Center Brilliance (Static)
                    drawCircle(
                        brush = highlightBrush,
                        radius = highlightRadius
                    )
                }
            }
        ) {}
    }

    private fun generateAuraPalette(seed: String): List<Color> {
        if (seed == "ANONYMOUS_AURA") {
            return listOf(
                Color(0xFF8E9EAB).copy(alpha = 0.6f),
                Color(0xFFBDC3C7).copy(alpha = 0.5f),
                Color(0xFF2C3E50).copy(alpha = 0.4f)
            )
        }

        val hash = seed.hashCode()
        val random = kotlin.random.Random(hash)
        val baseHue = abs(hash % 360).toFloat()
        
        return listOf(
            hslToColor(baseHue, 0.6f, 0.7f),
            hslToColor((baseHue + 30) % 360, 0.5f, 0.6f),
            hslToColor((baseHue + 60) % 360, 0.4f, 0.5f)
        ).shuffled(random)
    }

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
}
