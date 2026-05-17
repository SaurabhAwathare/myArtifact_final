package com.saurabh.artifact.presentation.avatar.renderer

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.caverock.androidsvg.SVG
import com.saurabh.artifact.R
import com.saurabh.artifact.presentation.avatar.model.AvatarConfig
import com.saurabh.artifact.presentation.avatar.model.AvatarLayer
import com.saurabh.artifact.presentation.avatar.model.EmotionState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp

@Composable
fun AvatarRenderer(
    config: AvatarConfig,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val layers = remember(config) { buildAvatarLayers(config) }
    
    // Subtle breathing animation
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "breathing"
    )

    Canvas(modifier = modifier.scale(breathingScale)) {
        layers.sortedBy { it.zIndex }.forEach { layer ->
            try {
                // Primitive drawing logic for better reliability in this environment
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                }
                
                drawIntoCanvas { canvas ->
                    val nativeCanvas = canvas.nativeCanvas
                    when (layer.resourceId) {
                        R.raw.face_base -> {
                            paint.color = layer.tint?.let {
                                android.graphics.Color.argb(
                                    (it.alpha * 255).toInt(),
                                    (it.red * 255).toInt(),
                                    (it.green * 255).toInt(),
                                    (it.blue * 255).toInt()
                                )
                            } ?: android.graphics.Color.parseColor("#FFDBAC")
                            nativeCanvas.drawCircle(size.width / 2f, size.height / 2f, size.width / 2.2f, paint)
                        }
                        R.raw.eyes_neutral -> {
                            paint.color = android.graphics.Color.BLACK
                            // Dynamic eye openness based on configuration could go here
                            nativeCanvas.drawCircle(size.width * 0.33f, size.height * 0.45f, size.width * 0.06f, paint)
                            nativeCanvas.drawCircle(size.width * 0.67f, size.height * 0.45f, size.width * 0.06f, paint)
                        }
                        R.raw.mouth_neutral -> {
                            paint.color = android.graphics.Color.BLACK
                            paint.style = android.graphics.Paint.Style.STROKE
                            paint.strokeWidth = 8f
                            paint.strokeCap = android.graphics.Paint.Cap.ROUND
                            canvas.nativeCanvas.drawArc(
                                size.width * 0.35f, size.height * 0.5f,
                                size.width * 0.65f, size.height * 0.7f,
                                20f, 140f, false, paint
                            )
                        }
                        else -> {
                            // Fallback to SVG for other layers
                            val svg = SVG.getFromResource(context, layer.resourceId)
                            svg.renderToCanvas(nativeCanvas)
                        }
                    }
                }
            } catch (e: Exception) {
                // Fallback or logging
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF0F0F0)
@Composable
fun AvatarPreview() {
    AvatarRenderer(
        config = AvatarConfig(
            faceId = "base",
            hairId = "none",
            eyesId = "neutral",
            mouthId = "neutral",
            skinTone = Color(0xFFFFDBAC),
            hairColor = Color.Black,
            emotion = EmotionState.CALM
        ),
        modifier = Modifier
            .size(200.dp)
            .background(Color.White)
            .padding(16.dp)
    )
}

/**
 * Maps the AvatarConfig to a prioritized list of SVG layers.
 * In a real production environment, this would resolve string IDs to R.raw resource IDs.
 */
private fun buildAvatarLayers(config: AvatarConfig): List<AvatarLayer> {
    return listOf(
        AvatarLayer(R.raw.face_base, config.skinTone, 0),
        AvatarLayer(R.raw.eyes_neutral, null, 1),
        AvatarLayer(R.raw.mouth_neutral, null, 2)
    )
}
