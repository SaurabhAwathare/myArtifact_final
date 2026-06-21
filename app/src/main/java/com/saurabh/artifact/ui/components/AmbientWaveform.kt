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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.components.waveform.FeedWaveformRenderer
import com.saurabh.artifact.ui.components.waveform.rememberFeedWaveform
import com.saurabh.artifact.util.WaveformProcessor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

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
    liveAmplitude: Float? = null,
    samplingMode: WaveformProcessor.SamplingMode = WaveformProcessor.SamplingMode.COMPRESS,
    context: WaveformContext = WaveformContext.Player,
    id: String = ""
) {
    if (isStatic && context == WaveformContext.Feed) {
        StaticWaveform(
            id = id,
            amplitudes = amplitudes,
            context = context,
            modifier = modifier
        )
        return
    }

    val activeColor = if (context == WaveformContext.Recording) GoldAura500 else ArtifactTheme.colors.waveformActive
    val inactiveColor = ArtifactTheme.colors.waveformInactive
    val emotionalTokens = ArtifactTheme.emotional

    // 1. Smooth Progress Interpolation
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = if (isStatic) snap() else tween(300, easing = LinearOutSlowInEasing),
        label = "WaveformProgress"
    )

    // 1b. Live Amplitude Animation (Ultra-fast for Recording)
    val animatedLiveAmplitude by animateFloatAsState(
        targetValue = liveAmplitude ?: 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "LiveAmplitude"
    )

    // 2. Breathing Animation (Idle State) - ONLY if not static
    val breathingScale = if (isStatic || context == WaveformContext.Recording) 1f else {
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

    // 3. Horizontal Drift (The Resonance) - ONLY if not static and NOT recording
    val driftPhase = if (isStatic || context == WaveformContext.Recording) 0f else {
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
    val processedAmplitudes = remember(amplitudes, context, samplingMode) {
        WaveformProcessor.process(amplitudes, context.barCount, samplingMode)
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
                    val driftOffset = if (!isPaused && !isStatic && context != WaveformContext.Recording) {
                        driftPhase * cycleWidth
                    } else 0f

                    // Recording Glow Effect
                    if (context == WaveformContext.Recording && animatedLiveAmplitude > 0.1f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(activeColor.copy(alpha = 0.15f * animatedLiveAmplitude), Color.Transparent),
                                center = Offset(size.width - 40.dp.toPx(), centerY),
                                radius = 80.dp.toPx()
                            ),
                            radius = 80.dp.toPx(),
                            center = Offset(size.width - 40.dp.toPx(), centerY)
                        )
                    }

                    processedAmplitudes.forEachIndexed { index, amp ->
                        val x = index * cycleWidth + driftOffset
                        val rawHeight = amp * size.height * 0.8f
                        
                        // If recording and it's the last bar, use the live amplitude
                        val height = if (context == WaveformContext.Recording && index == processedAmplitudes.size - 1) {
                            (animatedLiveAmplitude * size.height * 0.8f).coerceAtLeast(4.dp.toPx())
                        } else if (isPaused) {
                            rawHeight * breathingScale
                        } else {
                            rawHeight
                        }
                        
                        val barProgress = index.toFloat() / (processedAmplitudes.size - 1)
                        val isActive = if (context == WaveformContext.Recording) true else barProgress <= animatedProgress
                        
                        val color = if (isActive) {
                            activeColor
                        } else {
                            inactiveColor.copy(alpha = 0.3f)
                        }

                        // Draw subtle glow shadow for active bars
                        if (isActive && !isStatic) {
                            val alphaMult = if (context == WaveformContext.Recording) {
                                // Fade out older bars in recording
                                (index.toFloat() / processedAmplitudes.size).coerceIn(0.2f, 1f)
                            } else 1f

                            drawRoundRect(
                                color = activeColor.copy(alpha = glowIntensity * 0.5f * alphaMult),
                                topLeft = Offset(x - 2f, centerY - (height + 4f) / 2f),
                                size = Size(barWidthPx + 4f, height + 4f),
                                cornerRadius = cornerRadius
                            )
                        }

                        val barAlpha = if (context == WaveformContext.Recording) {
                            (index.toFloat() / processedAmplitudes.size).coerceIn(0.3f, 1f)
                        } else 1f

                        drawRoundRect(
                            color = color.copy(alpha = color.alpha * barAlpha),
                            topLeft = Offset(x, centerY - height / 2f),
                            size = Size(barWidthPx, height),
                            cornerRadius = cornerRadius
                        )

                        // 6. The "Pulse of Presence" (Leading Edge)
                        if (context != WaveformContext.Recording) {
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
            }
    ) {
        // Content drawn via drawWithCache
    }
}

/**
 * A static, full-width representation of audio data for feed cards.
 * Uses real data if available, otherwise falls back to a stable placeholder.
 */
@Composable
private fun StaticWaveform(
    id: String,
    amplitudes: List<Float>,
    context: WaveformContext,
    modifier: Modifier = Modifier
) {
    val waveform = rememberFeedWaveform(
        id = id,
        amplitudeData = amplitudes,
        barCount = context.barCount
    )
    
    val color = ArtifactTheme.colors.waveformActive.copy(alpha = 0.50f)

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

                waveform.forEachIndexed { index, amp ->
                    val x = index * cycleWidth
                    val height = (amp * size.height * 0.7f).coerceAtLeast(4.dp.toPx())
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

/**
 * An extremely lightweight waveform skeleton for initial rendering.
 * Uses a single draw call with fixed values to avoid CPU processing.
 * 
 * Refactor: Now fills the full barCount to maintain visual balance.
 */
@Composable
fun StaticWaveformPlaceholder(
    modifier: Modifier = Modifier,
    context: WaveformContext,
    id: String = "placeholder"
) {
    val color = ArtifactTheme.colors.waveformActive.copy(alpha = 0.35f)
    val pattern = remember(id, context.barCount) {
        FeedWaveformRenderer.generatePlaceholderPattern(
            id, 
            context.barCount
        )
    }
    
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

                pattern.forEachIndexed { index, amp ->
                    val x = index * cycleWidth
                    val height = (amp * size.height * 0.6f).coerceAtLeast(2.dp.toPx())
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
    Feed(barCount = 50, barWidth = 2.dp, gap = 2.dp, height = 40.dp),
    Player(barCount = 100, barWidth = 3.dp, gap = 2.dp, height = 120.dp),
    Recording(barCount = 60, barWidth = 4.dp, gap = 3.dp, height = 80.dp),
    Mini(barCount = 30, barWidth = 1.5.dp, gap = 1.5.dp, height = 20.dp)
}

private val SineEaseInOut = Easing { fraction ->
    -(cos(PI * fraction).toFloat() - 1f) / 2f
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A)
@Composable
fun PreviewAmbientWaveform() {
    ArtifactTheme {
        val mockAmplitudes = List(100) { (Random.nextFloat() * 0.8f) + 0.1f }
        AmbientWaveform(
            amplitudes = mockAmplitudes,
            progress = 0.4f,
            isPaused = true,
            context = WaveformContext.Player
        )
    }
}
