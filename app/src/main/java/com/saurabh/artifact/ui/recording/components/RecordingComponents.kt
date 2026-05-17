package com.saurabh.artifact.ui.recording.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.ui.theme.*

@Composable
fun ReflectionPromptSection(
    prompt: ReflectionPrompt?,
    onNext: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (prompt == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = prompt.question,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = FontFamily.Serif,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Light
            ),
            color = ReflectionWhite,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onNext,
                colors = ButtonDefaults.textButtonColors(contentColor = GoldAura400.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Next Prompt", style = MaterialTheme.typography.labelMedium)
            }

            TextButton(
                onClick = onHide,
                colors = ButtonDefaults.textButtonColors(contentColor = MistGray.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Hide", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun RecordingWaveform(
    amplitude: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "MicGlow")
    
    val baseGlowScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BaseGlow"
    )

    val animatedAmplitude by animateFloatAsState(
        targetValue = if (isRecording) amplitude else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "AnimatedAmplitude"
    )

    Box(
        modifier = modifier.size(300.dp),
        contentAlignment = Alignment.Center
    ) {
        // 1. Atmosphere: Breathing Mic Glow (Amber/Red)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(baseGlowScale + (animatedAmplitude * 0.4f))
                .alpha(0.08f + (animatedAmplitude * 0.15f))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFFB300), // Amber
                            Color(0xFFC0392B), // Soft Red
                            Color.Transparent
                        )
                    )
                )
        )

        // 2. Core: Organic Reactive Waveform
        Canvas(modifier = Modifier.size(240.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val barCount = 12
            val spacing = 16.dp.toPx()
            val baseBarWidth = 4.dp.toPx()
            
            for (i in 0 until barCount) {
                val distanceFromCenter = kotlin.math.abs(i - (barCount / 2f))
                val heightFactor = (1f - (distanceFromCenter / (barCount / 2f))).coerceIn(0.1f, 1f)
                
                // Organic movement
                val individualAmplitude = animatedAmplitude * heightFactor
                val barHeight = (40.dp.toPx() + (individualAmplitude * 120.dp.toPx()))
                
                val x = center.x + (i - barCount / 2) * spacing
                
                drawLine(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFC0392B).copy(alpha = 0.4f),
                            Color(0xFFFFB300),
                            Color(0xFFC0392B).copy(alpha = 0.4f)
                        )
                    ),
                    start = Offset(x, center.y - barHeight / 2),
                    end = Offset(x, center.y + barHeight / 2),
                    strokeWidth = baseBarWidth + (animatedAmplitude * 4.dp.toPx()),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
fun RecordingIndicator(
    isRecording: Boolean,
    isPreparing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RecordingIndicator")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "DotAlpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(if (isRecording || isPreparing) alpha else 0.3f)
                .background(
                    when {
                        isPreparing -> Color.White.copy(alpha = 0.4f)
                        isRecording -> Color.Red
                        else -> Color.Gray
                    }, 
                    CircleShape
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = when {
                isPreparing -> "Preparing..."
                isRecording -> "Recording"
                else -> "Paused"
            },
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.8f),
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun PrivacyRitualOverlay(
    isVisible: Boolean
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(600)),
        exit = fadeOut(animationSpec = tween(800))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Obsidian950),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val infiniteTransition = rememberInfiniteTransition(label = "RitualPulse")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 0.95f,
                    targetValue = 1.05f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = EaseInOutSine),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "PulseScale"
                )

                Box(
                    modifier = Modifier
                        .size(120.dp * scale)
                        .background(
                            Brush.radialGradient(
                                listOf(GoldAura500.copy(alpha = 0.2f), Color.Transparent)
                            )
                        )
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "Your thoughts are for you alone.",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic
                    ),
                    color = ReflectionWhite.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
