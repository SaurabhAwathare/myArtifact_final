package com.saurabh.artifact.ui.components.recording

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.ui.theme.ArtifactTheme

/**
 * AuraDock: A refined voice-entry point designed for calm technology.
 * Transitioned from abstract ritual to clear, emotionally safe invitation.
 * Uses a single-gold system and stable motion governance.
 */
@Composable
fun AuraDock(
    onInitiate: () -> Unit,
    modifier: Modifier = Modifier,
    status: RecordingStatus = RecordingStatus.IDLE,
    isVisible: Boolean = true
) {
    if (!isVisible) return

    val haptic = LocalHapticFeedback.current
    val infiniteTransition = rememberInfiniteTransition(label = "AuraBreathing")
    
    // Part 6 — Motion Restraint: Diaphragmatic Breathing
    // Slower, deeper, more stable than previous implementation.
    val breathingDuration = when (status) {
        RecordingStatus.RECORDING -> 3000 // Deep focus
        RecordingStatus.PAUSED -> 8000    // Suspended animation
        else -> 5000                     // Calm idle
    }

    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (status == RecordingStatus.RECORDING) 1.04f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(breathingDuration, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "StableBreath"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = if (status == RecordingStatus.RECORDING) 0.4f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(breathingDuration, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "AtmosphericGlow"
    )

    // Part 2 — Visual Hierarchy & Ritual Positioning
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(110.dp) // Slightly larger container for better glow diffusion
            .graphicsLayer { 
                scaleX = breathingScale
                scaleY = breathingScale 
            }
    ) {
        // Part 4, 7 & 8 — Glow & Atmosphere Governance
        AuraGlow(
            alpha = glowAlpha
        )

        // The Core Interaction Surface
        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onInitiate()
            },
            shape = CircleShape,
            color = if (status == RecordingStatus.RECORDING) Color(0xFFD96B5F) else Color(0xFFD96B5F),
            tonalElevation = 6.dp,
            modifier = Modifier
                .size(72.dp) // Slightly larger
                .clip(CircleShape),
            interactionSource = remember { MutableInteractionSource() }
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Part 5 — Microphone Symbol System
                MicrophoneGlyph(status = status)
            }
        }
    }
}

@Composable
private fun AuraGlow(alpha: Float) {
    // Part 4 — Emotional Red Glow
    val color = Color(0xFFD96B5F)
    
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .blur(32.dp)
            .graphicsLayer { this.alpha = alpha }
    ) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color,
                    Color.Transparent
                ),
                center = center,
                radius = size.minDimension / 2.2f
            )
        )
    }
}

@Composable
private fun MicrophoneGlyph(status: RecordingStatus) {
    // Part 5 & 6 — Symbolic Clarity & Motion Restraint
    val transitionProgress by animateFloatAsState(
        targetValue = if (status == RecordingStatus.RECORDING) 1f else 0f,
        animationSpec = tween(600, easing = EaseInOutCubic),
        label = "MicTransition"
    )

    val coreColor by animateColorAsState(
        targetValue = Color.White,
        animationSpec = tween(600),
        label = "MicColor"
    )

    Canvas(
        modifier = Modifier
            .size(24.dp)
            .graphicsLayer {
                // Subtle scale up when recording
                val scale = 1f + (transitionProgress * 0.1f)
                scaleX = scale
                scaleY = scale
            }
    ) {
        val strokeWidth = 2.dp.toPx()
        val cap = StrokeCap.Round
        
        // --- 1. The Mic Capsule ---
        val capsuleWidth = 8.dp.toPx()
        val capsuleHeight = 12.dp.toPx()
        val capsuleRect = Rect(
            center.x - capsuleWidth / 2,
            center.y - capsuleHeight / 2 - 2.dp.toPx(),
            center.x + capsuleWidth / 2,
            center.y + capsuleHeight / 2 - 2.dp.toPx()
        )
        
        drawRoundRect(
            color = coreColor,
            topLeft = capsuleRect.topLeft,
            size = capsuleRect.size,
            cornerRadius = CornerRadius(capsuleWidth / 2),
            style = if (status == RecordingStatus.RECORDING) Fill else Stroke(width = strokeWidth)
        )

        // --- 2. The Stand (U-Shape) ---
        val standRadius = 8.dp.toPx()
        val standTop = center.y - 1.dp.toPx()
        
        drawArc(
            color = coreColor,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(center.x - standRadius, standTop - standRadius),
            size = Size(standRadius * 2, standRadius * 2),
            style = Stroke(width = strokeWidth, cap = cap)
        )

        // --- 3. The Stem ---
        drawLine(
            color = coreColor,
            start = Offset(center.x, standTop + standRadius),
            end = Offset(center.x, standTop + standRadius + 4.dp.toPx()),
            strokeWidth = strokeWidth,
            cap = cap
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun PreviewAuraDockIdle() {
    ArtifactTheme {
        Box(modifier = Modifier.padding(20.dp)) {
            AuraDock(onInitiate = {}, status = RecordingStatus.IDLE)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun PreviewAuraDockRecording() {
    ArtifactTheme {
        Box(modifier = Modifier.padding(20.dp)) {
            AuraDock(onInitiate = {}, status = RecordingStatus.RECORDING)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun PreviewAuraDockPaused() {
    ArtifactTheme {
        Box(modifier = Modifier.padding(20.dp)) {
            AuraDock(onInitiate = {}, status = RecordingStatus.PAUSED)
        }
    }
}
