package com.saurabh.artifact.ui.avatar

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.caverock.androidsvg.SVG
import com.saurabh.artifact.model.AvatarConfig
import com.saurabh.artifact.model.AvatarExpression

@Composable
fun AvatarRenderer(
    config: AvatarConfig,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val infiniteTransition = rememberInfiniteTransition(label = "avatar_motion")
    
    val breathScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing"
    )

    val blinkProgress by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 5000
                1f at 0
                1f at 4800
                0f at 4900
                1f at 5000
            }
        ),
        label = "blinking"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = breathScale

            // 1. Ambient Glow
            if (config.glowEnabled) {
                drawAmbientGlow(config.getAmbientGlowColor())
            }

            // 2. Base Body & Head
            drawSvgLayer(context, "avatar/base/${config.headId}.svg", config.getSkinToneColor(), scale)

            // 3. Outfit
            drawSvgLayer(context, "avatar/outfit/${config.outfitId}.svg", config.getOutfitColor(), scale)

            // 4. Facial Features (Eyes & Mouth)
            // Note: Blink logic handled here
            val eyeId = if (blinkProgress < 0.1f) "eye_closed" else config.eyeId
            drawSvgLayer(context, "avatar/face/$eyeId.svg", null, scale)
            drawSvgLayer(context, "avatar/face/${config.mouthId}.svg", null, scale)

            // 5. Hair
            drawSvgLayer(context, "avatar/hair/${config.hairId}.svg", config.getHairColor(), scale)
        }
    }
}

private fun DrawScope.drawAmbientGlow(color: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.4f), Color.Transparent),
            center = center,
            radius = size.minDimension * 0.45f
        ),
        radius = size.minDimension * 0.45f
    )
}

private fun DrawScope.drawSvgLayer(
    context: Context,
    assetPath: String,
    tint: Color?,
    scale: Float
) {
    try {
        val svg = SVG.getFromAsset(context.assets, assetPath)
        
        // Apply tint if provided (using ColorFilter on the native canvas)
        val paint = android.graphics.Paint().apply {
            if (tint != null) {
                colorFilter = android.graphics.PorterDuffColorFilter(
                    tint.toArgb(),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            }
        }

        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.save()
            
            // Center and scale
            canvas.nativeCanvas.translate(size.width / 2, size.height / 2)
            canvas.nativeCanvas.scale(scale, scale)
            
            // Calculate SVG bounds to center it
            val documentWidth = svg.documentWidth
            val documentHeight = svg.documentHeight
            
            // Scale SVG to fit canvas (preserving aspect ratio)
            val canvasScale = (size.minDimension / maxOf(documentWidth, documentHeight)) * 0.8f
            canvas.nativeCanvas.scale(canvasScale, canvasScale)
            
            canvas.nativeCanvas.translate(-documentWidth / 2, -documentHeight / 2)
            
            // Apply tint using saveLayer if tint is present
            if (tint != null) {
                canvas.nativeCanvas.saveLayer(null, paint)
                svg.renderToCanvas(canvas.nativeCanvas)
                canvas.nativeCanvas.restore()
            } else {
                svg.renderToCanvas(canvas.nativeCanvas)
            }
            
            canvas.nativeCanvas.restore()
        }
    } catch (e: Exception) {
        // Fallback: If asset not found, draw a placeholder or log
    }
}
