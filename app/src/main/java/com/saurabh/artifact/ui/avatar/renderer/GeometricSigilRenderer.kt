package com.saurabh.artifact.ui.avatar.renderer

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.saurabh.artifact.model.SigilConfig
import com.saurabh.artifact.model.avatar.*
import java.util.Random

class GeometricSigilRenderer : SigilRenderer {

    @Composable
    override fun Render(
        config: SigilConfig,
        animationState: SigilAnimationState,
        modifier: Modifier
    ) {
        val colors = remember(config.palette) { getPaletteColors(config.palette) }
        
        Canvas(
            modifier = modifier.drawWithCache {
                val scale = size.minDimension / 100f
                val center = Offset(size.width / 2f, size.height / 2f)
                
                // Deterministic Random based on seed
                val random = Random(config.seed.hashCode().toLong())
                
                // Pre-generate primitives to avoid allocations in DrawScope
                val primitives = List(3 + random.nextInt(3)) {
                    SigilPrimitive.generate(random, colors)
                }

                onDrawBehind {
                    rotate(animationState.rotation() * 360f, center) {
                        primitives.forEach { primitive ->
                            drawPrimitive(primitive, config, scale, center, animationState.pulse())
                        }
                    }
                }
            }
        ) {}
    }

    private fun DrawScope.drawPrimitive(
        primitive: SigilPrimitive,
        config: SigilConfig,
        scale: Float,
        center: Offset,
        pulse: Float
    ) {
        val alpha = if (config.variant == SigilVariant.GHOST) 0.4f else 1.0f
        val color = primitive.color.copy(alpha = alpha)
        val strokeWidth = config.weight * scale * pulse
        val style = when (config.style) {
            SigilStyle.OUTLINE -> Stroke(width = strokeWidth)
            SigilStyle.FILLED -> Fill
            SigilStyle.MIXED -> if (primitive.isOutline) Stroke(width = strokeWidth) else Fill
        }

        val offset = Offset(
            center.x + (primitive.offsetX * scale),
            center.y + (primitive.offsetY * scale)
        )
        val size = primitive.size * scale * pulse

        when (primitive.type) {
            PrimitiveType.CIRCLE -> {
                drawCircle(
                    color = color,
                    radius = size / 2f,
                    center = offset,
                    style = style
                )
            }
            PrimitiveType.SQUARE -> {
                drawRect(
                    color = color,
                    topLeft = Offset(offset.x - size / 2f, offset.y - size / 2f),
                    size = Size(size, size),
                    style = style
                )
            }
            PrimitiveType.TRIANGLE -> {
                val path = Path().apply {
                    moveTo(offset.x, offset.y - size / 2f)
                    lineTo(offset.x + size / 2f, offset.y + size / 2f)
                    lineTo(offset.x - size / 2f, offset.y + size / 2f)
                    close()
                }
                drawPath(path, color = color, style = style)
            }
            PrimitiveType.ARC -> {
                drawArc(
                    color = color,
                    startAngle = primitive.startAngle,
                    sweepAngle = primitive.sweepAngle,
                    useCenter = false,
                    topLeft = Offset(offset.x - size / 2f, offset.y - size / 2f),
                    size = Size(size, size),
                    style = style
                )
            }
        }
    }

    private fun getPaletteColors(palette: SigilPalette): List<Color> {
        return when (palette) {
            SigilPalette.AURORA -> listOf(Color(0xFF00D2FF), Color(0xFF92FE9D), Color(0xFF0072FF))
            SigilPalette.EMBER -> listOf(Color(0xFFFF512F), Color(0xFFDD2476), Color(0xFFFF9966))
            SigilPalette.FOREST -> listOf(Color(0xFF5A3F37), Color(0xFF2C7744), Color(0xFFA2D149))
            SigilPalette.MONO -> listOf(Color(0xFF232526), Color(0xFF414345), Color(0xFFFFFFFF))
            SigilPalette.CELESTIAL -> listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
        }
    }
}

private enum class PrimitiveType { CIRCLE, SQUARE, TRIANGLE, ARC }

private data class SigilPrimitive(
    val type: PrimitiveType,
    val color: Color,
    val size: Float,
    val offsetX: Float,
    val offsetY: Float,
    val isOutline: Boolean,
    val startAngle: Float = 0f,
    val sweepAngle: Float = 0f
) {
    companion object {
        fun generate(random: Random, colors: List<Color>): SigilPrimitive {
            return SigilPrimitive(
                type = PrimitiveType.entries[random.nextInt(PrimitiveType.entries.size)],
                color = colors[random.nextInt(colors.size)],
                size = 20f + random.nextFloat() * 60f,
                offsetX = (random.nextFloat() - 0.5f) * 40f,
                offsetY = (random.nextFloat() - 0.5f) * 40f,
                isOutline = random.nextBoolean(),
                startAngle = random.nextFloat() * 360f,
                sweepAngle = 90f + random.nextFloat() * 180f
            )
        }
    }
}
