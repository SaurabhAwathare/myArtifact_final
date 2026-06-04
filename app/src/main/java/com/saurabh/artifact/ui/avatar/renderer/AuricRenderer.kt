package com.saurabh.artifact.ui.avatar.renderer

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.saurabh.artifact.model.AvatarConfig
import kotlin.math.abs

class AuricRenderer : AvatarRenderer {

    override fun DrawScope.render(config: AvatarConfig, animationState: AvatarAnimationState) {
        val palette = generateAuraPalette(config.seed)
        val sizePx = size.minDimension

        // 1. Base Layer: Soft Ambient Glow
        drawAuraLayer(
            colors = palette.take(2),
            radiusMult = 0.5f * animationState.pulse,
            alpha = 0.4f
        )

        // 2. Core Identity Layer: Multi-color Gradient
        drawAuraLayer(
            colors = palette,
            radiusMult = 0.35f,
            alpha = 0.8f,
            rotation = animationState.rotation
        )

        // 3. Highlight Layer: Center Brilliance
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.3f),
                0.6f to Color.Transparent,
                center = center,
                radius = sizePx * 0.25f
            ),
            radius = sizePx * 0.25f
        )
    }

    private fun DrawScope.drawAuraLayer(
        colors: List<Color>,
        radiusMult: Float,
        alpha: Float,
        rotation: Float = 0f
    ) {
        val radius = size.maxDimension * radiusMult
        
        drawCircle(
            brush = Brush.linearGradient(
                colors = colors.map { it.copy(alpha = it.alpha * alpha) }
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
