package com.saurabh.artifact.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.util.WaveformProcessor

/**
 * AmbientWaveform - A premium, atmospheric audio visualization.
 * Designed for emotional resonance and calm interaction.
 */
@Composable
fun AmbientWaveform(
    amplitudes: List<Float>,
    progress: Float,
    modifier: Modifier = Modifier,
    isPaused: Boolean = false,
    isStatic: Boolean = false,
    context: WaveformContext = WaveformContext.Player
) {
    if (isStatic && context == WaveformContext.Feed) {
        StaticWaveformPlaceholder(modifier, context)
        return
    }

    val activeColor = ArtifactTheme.colors.waveformActive
    val inactiveColor = ArtifactTheme.colors.waveformInactive
    val emotionalTokens = ArtifactTheme.emotional

    // 1. Smooth Progress Interpolation
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = if (isStatic) snap() else tween(300, easing = LinearOutSlowInEasing),
        label = "WaveformProgress"
    )

    // 2. Breathing Animation (Idle State) - ONLY if not static
    val breathingScale = if (isStatic) 1f else {
        val infiniteTransition = rememberInfiniteTransition(label = "WaveformBreathing")
        infiniteTransition.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(emotionalTokens.breathingRateSlow, easing = SineEaseInOut),
                repeatMode = RepeatMode.Reverse
            ),
            label = "Breathing"
        ).value
    }

    // 3. Horizontal Drift (The Resonance) - ONLY if not static
    val driftPhase = if (isStatic) 0f else {
        val infiniteTransition = rememberInfiniteTransition(label = "WaveformDrift")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(15000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "Drift"
        ).value
    }

    // 4. Amplitude Glow Intensity - ONLY if not static
    val glowIntensity by animateFloatAsState(
        targetValue = if (isPaused || isStatic) 0.1f else 0.4f,
        animationSpec = if (isStatic) snap() else tween(1000),
        label = "WaveformGlow"
    )

    // 5. Process Amplitudes based on Context
    val processedAmplitudes = remember(amplitudes, context) {
        WaveformProcessor.process(amplitudes, context.barCount)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(context.height)
            .graphicsLayer(alpha = 0.99f)
            .drawWithCache {
                onDrawWithContent {
                    val barWidthPx = context.barWidth.toPx()
                    val gapPx = context.gap.toPx()
                    val cornerRadius = CornerRadius(barWidthPx / 2f)
                    val centerY = size.height / 2f
                    
                    val cycleWidth = barWidthPx + gapPx
                    val driftOffset = if (!isPaused && !isStatic) driftPhase * cycleWidth else 0f

                    processedAmplitudes.forEachIndexed { index, amp ->
                        val x = index * cycleWidth + driftOffset
                        val rawHeight = amp * size.height * 0.8f
                        
                        val height = if (isPaused) rawHeight * breathingScale else rawHeight
                        
                        val barProgress = index.toFloat() / processedAmplitudes.size
                        val isActive = barProgress <= animatedProgress
                        
                        val color = if (isActive) {
                            activeColor
                        } else {
                            inactiveColor.copy(alpha = 0.3f)
                        }

                        // Draw subtle glow shadow for active bars
                        if (isActive && !isStatic) {
                            drawRoundRect(
                                color = activeColor.copy(alpha = glowIntensity * 0.5f),
                                topLeft = Offset(x - 2f, centerY - (height + 4f) / 2f),
                                size = Size(barWidthPx + 4f, height + 4f),
                                cornerRadius = cornerRadius
                            )
                        }

                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, centerY - height / 2f),
                            size = Size(barWidthPx, height),
                            cornerRadius = cornerRadius
                        )

                        // 6. The "Pulse of Presence" (Leading Edge)
                        val progressIndex = (animatedProgress * (processedAmplitudes.size - 1)).toInt()
                        if (isActive && index == progressIndex && !isStatic) {
                            drawCircle(
                                color = activeColor.copy(alpha = glowIntensity),
                                radius = height * 0.6f,
                                center = Offset(x + barWidthPx / 2f, centerY)
                            )
                        }
                    }
                }
            }
    ) {
        // Content drawn via drawWithCache
    }
}

/**
 * An extremely lightweight waveform skeleton for initial rendering.
 * Uses a single draw call with fixed values to avoid CPU processing.
 */
@Composable
fun StaticWaveformPlaceholder(
    modifier: Modifier = Modifier,
    context: WaveformContext
) {
    val color = ArtifactTheme.colors.waveformInactive.copy(alpha = 0.1f)
    
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(context.height)
            .drawBehind {
                val barWidthPx = context.barWidth.toPx()
                val gapPx = context.gap.toPx()
                val centerY = size.height / 2f
                val cycleWidth = barWidthPx + gapPx
                val cornerRadius = CornerRadius(barWidthPx / 2f)

                // Draw a fixed pattern of 12 bars to represent a signature
                val pattern = listOf(0.4f, 0.6f, 0.5f, 0.8f, 0.3f, 0.7f, 0.5f, 0.4f, 0.6f, 0.9f, 0.5f, 0.4f)
                pattern.forEachIndexed { index, amp ->
                    val x = index * cycleWidth
                    val height = amp * size.height * 0.6f
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, centerY - height / 2f),
                        size = Size(barWidthPx, height),
                        cornerRadius = cornerRadius
                    )
                }
            }
    )
}

enum class WaveformContext(
    val barCount: Int,
    val barWidth: androidx.compose.ui.unit.Dp,
    val gap: androidx.compose.ui.unit.Dp,
    val height: androidx.compose.ui.unit.Dp
) {
    Feed(barCount = 40, barWidth = 2.dp, gap = 2.dp, height = 40.dp),
    Player(barCount = 100, barWidth = 3.dp, gap = 2.dp, height = 120.dp),
    Recording(barCount = 60, barWidth = 4.dp, gap = 3.dp, height = 80.dp),
    Mini(barCount = 30, barWidth = 1.5.dp, gap = 1.5.dp, height = 20.dp)
}

private val SineEaseInOut = Easing { fraction ->
    -(Math.cos(Math.PI * fraction).toFloat() - 1f) / 2f
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A)
@Composable
fun PreviewAmbientWaveform() {
    ArtifactTheme {
        val mockAmplitudes = List(100) { (Math.random().toFloat() * 0.8f) + 0.1f }
        AmbientWaveform(
            amplitudes = mockAmplitudes,
            progress = 0.4f,
            isPaused = true,
            context = WaveformContext.Player
        )
    }
}
