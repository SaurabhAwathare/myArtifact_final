package com.saurabh.artifact.ui.avatar.renderer

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.core.graphics.toColorInt
import com.saurabh.artifact.model.AvatarConfig
import com.saurabh.artifact.model.avatar.*

class CartoonRenderer : AvatarRenderer {

    @Composable
    override fun Render(
        config: AvatarConfig,
        animationState: AvatarAnimationState,
        modifier: Modifier
    ) {
        // Cache parsed colors to avoid per-frame parsing
        val skinColor = remember(config.skinColor) { Color(config.skinColor.toColorInt()) }
        
        Canvas(
            modifier = modifier.drawWithCache {
                val scale = size.minDimension / 100f // Normalize to 100x100 space
                val faceCenter = Offset(size.width / 2f, size.height / 2f)
                
                onDrawBehind {
                    drawFace(skinColor, faceCenter, scale)
                    drawEyes(config, faceCenter, scale)
                    drawMouth(config, faceCenter, scale)
                    drawAccessories(config, faceCenter, scale)
                }
            }
        ) {}
    }

    private fun DrawScope.drawFace(color: Color, faceCenter: Offset, scale: Float) {
        drawCircle(color = color, center = faceCenter, radius = 40f * scale)
    }

    private fun DrawScope.drawEyes(config: AvatarConfig, faceCenter: Offset, scale: Float) {
        val eyeColor = Color.Black
        val eyeY = faceCenter.y - 5f * scale
        val leftEyeX = faceCenter.x - 15f * scale
        val rightEyeX = faceCenter.x + 15f * scale
        val eyeSize = 6f * scale

        when (config.eyeType) {
            EyeType.NEUTRAL -> {
                drawCircle(color = eyeColor, radius = eyeSize, center = Offset(leftEyeX, eyeY))
                drawCircle(color = eyeColor, radius = eyeSize, center = Offset(rightEyeX, eyeY))
            }
            EyeType.HAPPY -> {
                drawArc(
                    color = eyeColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(leftEyeX - eyeSize, eyeY - eyeSize),
                    size = Size(eyeSize * 2, eyeSize * 2),
                    style = Stroke(width = 2f * scale)
                )
                drawArc(
                    color = eyeColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(rightEyeX - eyeSize, eyeY - eyeSize),
                    size = Size(eyeSize * 2, eyeSize * 2),
                    style = Stroke(width = 2f * scale)
                )
            }
            EyeType.SURPRISED -> {
                drawCircle(color = eyeColor, radius = eyeSize * 1.5f, center = Offset(leftEyeX, eyeY), style = Stroke(width = 2f * scale))
                drawCircle(color = eyeColor, radius = eyeSize * 1.5f, center = Offset(rightEyeX, eyeY), style = Stroke(width = 2f * scale))
            }
            EyeType.CLOSED -> {
                drawLine(color = eyeColor, start = Offset(leftEyeX - eyeSize, eyeY), end = Offset(leftEyeX + eyeSize, eyeY), strokeWidth = 2f * scale)
                drawLine(color = eyeColor, start = Offset(rightEyeX - eyeSize, eyeY), end = Offset(rightEyeX + eyeSize, eyeY), strokeWidth = 2f * scale)
            }
            EyeType.WINK -> {
                drawCircle(color = eyeColor, radius = eyeSize, center = Offset(leftEyeX, eyeY))
                drawArc(
                    color = eyeColor,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(rightEyeX - eyeSize, eyeY - eyeSize),
                    size = Size(eyeSize * 2, eyeSize * 2),
                    style = Stroke(width = 2f * scale)
                )
            }
        }
    }

    private fun DrawScope.drawMouth(config: AvatarConfig, faceCenter: Offset, scale: Float) {
        val mouthY = faceCenter.y + 20f * scale
        val mouthWidth = 20f * scale
        val mouthColor = Color.Black

        when (config.mouthType) {
            MouthType.NEUTRAL -> {
                drawLine(
                    color = mouthColor,
                    start = Offset(faceCenter.x - mouthWidth / 2, mouthY),
                    end = Offset(faceCenter.x + mouthWidth / 2, mouthY),
                    strokeWidth = 2f * scale
                )
            }
            MouthType.SMILE -> {
                drawArc(
                    color = mouthColor,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(faceCenter.x - mouthWidth / 2, mouthY - 5f * scale),
                    size = Size(mouthWidth, 10f * scale),
                    style = Stroke(width = 2f * scale)
                )
            }
            MouthType.LAUGH -> {
                drawArc(
                    color = mouthColor,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(faceCenter.x - mouthWidth / 2, mouthY - 5f * scale),
                    size = Size(mouthWidth, 15f * scale)
                )
            }
            MouthType.SAD -> {
                drawArc(
                    color = mouthColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(faceCenter.x - mouthWidth / 2, mouthY),
                    size = Size(mouthWidth, 10f * scale),
                    style = Stroke(width = 2f * scale)
                )
            }
            MouthType.SURPRISED -> {
                drawCircle(
                    color = mouthColor,
                    radius = 5f * scale,
                    center = Offset(faceCenter.x, mouthY)
                )
            }
        }
    }

    private fun DrawScope.drawAccessories(config: AvatarConfig, faceCenter: Offset, scale: Float) {
        val accColor = Color.DarkGray
        val eyeY = faceCenter.y - 5f * scale
        val leftEyeX = faceCenter.x - 15f * scale
        val rightEyeX = faceCenter.x + 15f * scale
        
        when (config.accessoryType) {
            AccessoryType.GLASSES -> {
                drawCircle(color = accColor, radius = 10f * scale, center = Offset(leftEyeX, eyeY), style = Stroke(width = 2f * scale))
                drawCircle(color = accColor, radius = 10f * scale, center = Offset(rightEyeX, eyeY), style = Stroke(width = 2f * scale))
                drawLine(color = accColor, start = Offset(faceCenter.x - 5f * scale, eyeY), end = Offset(faceCenter.x + 5f * scale, eyeY), strokeWidth = 2f * scale)
            }
            AccessoryType.SUNGLASSES -> {
                drawCircle(color = Color.Black, radius = 10f * scale, center = Offset(leftEyeX, eyeY))
                drawCircle(color = Color.Black, radius = 10f * scale, center = Offset(rightEyeX, eyeY))
                drawLine(color = Color.Black, start = Offset(faceCenter.x - 5f * scale, eyeY), end = Offset(faceCenter.x + 5f * scale, eyeY), strokeWidth = 4f * scale)
            }
            AccessoryType.HEADPHONES -> {
                drawArc(
                    color = accColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(faceCenter.x - 40f * scale, faceCenter.y - 40f * scale),
                    size = Size(80f * scale, 80f * scale),
                    style = Stroke(width = 6f * scale)
                )
                drawCircle(color = accColor, radius = 12f * scale, center = Offset(faceCenter.x - 35f * scale, faceCenter.y))
                drawCircle(color = accColor, radius = 12f * scale, center = Offset(faceCenter.x + 35f * scale, faceCenter.y))
            }
            else -> {}
        }
    }
}
