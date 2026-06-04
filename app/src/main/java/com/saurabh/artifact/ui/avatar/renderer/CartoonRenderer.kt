package com.saurabh.artifact.ui.avatar.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.saurabh.artifact.model.AvatarConfig
import com.saurabh.artifact.model.avatar.*

class CartoonRenderer : AvatarRenderer {

    override fun DrawScope.render(config: AvatarConfig, animationState: AvatarAnimationState) {
        val scale = size.minDimension / 100f // Normalize to 100x100 space
        
        drawFace(config, scale)
        drawEyes(config, scale)
        drawMouth(config, scale)
        drawHair(config, scale)
        drawAccessories(config, scale)
    }

    private fun DrawScope.drawFace(config: AvatarConfig, scale: Float) {
        val color = Color(android.graphics.Color.parseColor(config.skinColor))
        
        when (config.faceShape) {
            FaceShape.ROUND -> {
                drawCircle(color = color, center = center, radius = 40f * scale)
            }
            FaceShape.OVAL -> {
                drawOval(
                    color = color,
                    topLeft = Offset(15f * scale, 10f * scale),
                    size = Size(70f * scale, 85f * scale)
                )
            }
            FaceShape.SQUARE -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(15f * scale, 15f * scale),
                    size = Size(70f * scale, 75f * scale),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f * scale)
                )
            }
        }
    }

    private fun DrawScope.drawEyes(config: AvatarConfig, scale: Float) {
        val eyeColor = Color.Black
        val eyeY = 45f * scale
        val leftEyeX = 35f * scale
        val rightEyeX = 65f * scale
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

    private fun DrawScope.drawMouth(config: AvatarConfig, scale: Float) {
        val mouthY = 70f * scale
        val mouthWidth = 20f * scale
        val mouthColor = Color.Black

        when (config.mouthType) {
            MouthType.NEUTRAL -> {
                drawLine(
                    color = mouthColor,
                    start = Offset(50f * scale - mouthWidth / 2, mouthY),
                    end = Offset(50f * scale + mouthWidth / 2, mouthY),
                    strokeWidth = 2f * scale
                )
            }
            MouthType.SMILE -> {
                drawArc(
                    color = mouthColor,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(50f * scale - mouthWidth / 2, mouthY - 5f * scale),
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
                    topLeft = Offset(50f * scale - mouthWidth / 2, mouthY - 5f * scale),
                    size = Size(mouthWidth, 15f * scale)
                )
            }
            MouthType.SAD -> {
                drawArc(
                    color = mouthColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(50f * scale - mouthWidth / 2, mouthY),
                    size = Size(mouthWidth, 10f * scale),
                    style = Stroke(width = 2f * scale)
                )
            }
            MouthType.SURPRISED -> {
                drawCircle(
                    color = mouthColor,
                    radius = 5f * scale,
                    center = Offset(50f * scale, mouthY)
                )
            }
        }
    }

    private fun DrawScope.drawHair(config: AvatarConfig, scale: Float) {
        if (config.hairType == HairType.NONE) return
        
        val hairColor = Color(android.graphics.Color.parseColor(config.hairColor))
        
        when (config.hairType) {
            HairType.SHORT -> {
                drawArc(
                    color = hairColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(15f * scale, 10f * scale),
                    size = Size(70f * scale, 40f * scale)
                )
            }
            HairType.LONG -> {
                // Draw sides
                drawRect(
                    color = hairColor,
                    topLeft = Offset(15f * scale, 20f * scale),
                    size = Size(15f * scale, 60f * scale)
                )
                drawRect(
                    color = hairColor,
                    topLeft = Offset(70f * scale, 20f * scale),
                    size = Size(15f * scale, 60f * scale)
                )
                // Top
                drawArc(
                    color = hairColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(15f * scale, 10f * scale),
                    size = Size(70f * scale, 40f * scale)
                )
            }
            HairType.BOB -> {
                drawArc(
                    color = hairColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(10f * scale, 10f * scale),
                    size = Size(80f * scale, 60f * scale)
                )
            }
            HairType.SPIKY -> {
                val path = Path().apply {
                    moveTo(20f * scale, 40f * scale)
                    lineTo(25f * scale, 10f * scale)
                    lineTo(35f * scale, 30f * scale)
                    lineTo(50f * scale, 5f * scale)
                    lineTo(65f * scale, 30f * scale)
                    lineTo(75f * scale, 10f * scale)
                    lineTo(80f * scale, 40f * scale)
                    close()
                }
                drawPath(path = path, color = hairColor)
            }
            HairType.WAVE -> {
                drawArc(
                    color = hairColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(10f * scale, 10f * scale),
                    size = Size(80f * scale, 50f * scale)
                )
            }
            else -> {}
        }
    }

    private fun DrawScope.drawAccessories(config: AvatarConfig, scale: Float) {
        val accColor = Color.DarkGray
        val eyeY = 45f * scale
        
        when (config.accessoryType) {
            AccessoryType.GLASSES -> {
                drawCircle(color = accColor, radius = 10f * scale, center = Offset(35f * scale, eyeY), style = Stroke(width = 2f * scale))
                drawCircle(color = accColor, radius = 10f * scale, center = Offset(65f * scale, eyeY), style = Stroke(width = 2f * scale))
                drawLine(color = accColor, start = Offset(45f * scale, eyeY), end = Offset(55f * scale, eyeY), strokeWidth = 2f * scale)
            }
            AccessoryType.SUNGLASSES -> {
                drawCircle(color = Color.Black, radius = 10f * scale, center = Offset(35f * scale, eyeY))
                drawCircle(color = Color.Black, radius = 10f * scale, center = Offset(65f * scale, eyeY))
                drawLine(color = Color.Black, start = Offset(45f * scale, eyeY), end = Offset(55f * scale, eyeY), strokeWidth = 4f * scale)
            }
            AccessoryType.HEADPHONES -> {
                drawArc(
                    color = accColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(10f * scale, 10f * scale),
                    size = Size(80f * scale, 80f * scale),
                    style = Stroke(width = 6f * scale)
                )
                drawCircle(color = accColor, radius = 12f * scale, center = Offset(15f * scale, 50f * scale))
                drawCircle(color = accColor, radius = 12f * scale, center = Offset(85f * scale, 50f * scale))
            }
            else -> {}
        }
    }
}
