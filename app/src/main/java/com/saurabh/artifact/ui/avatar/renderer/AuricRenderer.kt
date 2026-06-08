package com.saurabh.artifact.ui.avatar.renderer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
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

        // Example of derivedStateOf: Optimization for logic that depends on high-frequency state
        // This only recomposes when the pulse crosses the "shimmer" threshold
        val isShimmering by remember {
            derivedStateOf { currentAnimationState.pulse() > 1.01f }
        }

        Box(modifier = modifier) {
            // 1. Base Layer: Soft Ambient Glow (Animated Pulse)
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val p = animationState.pulse()
                        scaleX = p
                        scaleY = p
                        alpha = if (isShimmering) 0.5f else 0.4f
                    }
                    .drawWithCache {
                        val colors = palette.take(2)
                        onDrawBehind {
                            drawAuraCircle(colors = colors, radiusMult = 0.5f)
                        }
                    }
            ) {}

            // 2. Core Identity Layer: Multi-color Gradient (Animated Rotation)
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        rotationZ = animationState.rotation()
                        alpha = 0.8f
                    }
                    .drawWithCache {
                        onDrawBehind {
                            drawAuraCircle(colors = palette, radiusMult = 0.35f)
                        }
                    }
            ) {}

            // 3. Highlight Layer: Center Brilliance (Static)
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val centerOffset = size.center
                        val radius = size.minDimension * 0.25f
                        val brush = Brush.radialGradient(
                            0f to Color.White.copy(alpha = 0.3f),
                            0.6f to Color.Transparent,
                            center = centerOffset,
                            radius = radius
                        )
                        onDrawBehind {
                            drawCircle(
                                brush = brush,
                                radius = radius
                            )
                        }
                    }
            ) {}
        }
    }

    private fun DrawScope.drawAuraCircle(
        colors: List<Color>,
        radiusMult: Float
    ) {
        val radius = size.maxDimension * radiusMult
        drawCircle(
            brush = Brush.linearGradient(
                colors = colors
            ),
            radius = radius
        )
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
