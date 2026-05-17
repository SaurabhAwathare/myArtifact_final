package com.saurabh.artifact.ui.recording.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NavigateNext
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.ui.theme.*

@Composable
fun NewPromptSection(
    category: String,
    question: String,
    onNextPrompt: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Top Label
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "☀",
                color = GoldAura500,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Today's $category".uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = GoldAura500.copy(alpha = 0.7f),
                letterSpacing = 2.sp
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Main Prompt
        Text(
            text = question,
            style = ArtifactTheme.typography.displayLarge.copy(
                fontSize = 36.sp,
                lineHeight = 48.sp,
                color = GoldAura400
            ),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Supportive Text
        Text(
            text = "Speak freely.\nThis is your private & anonymous space.",
            style = MaterialTheme.typography.bodySmall,
            color = ReflectionWhite.copy(alpha = 0.4f),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Next Question Button
        Surface(
            onClick = onNextPrompt,
            color = Color.Transparent,
            shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, GoldAura500.copy(alpha = 0.3f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Next Question",
                    style = MaterialTheme.typography.labelMedium,
                    color = GoldAura500.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.NavigateNext,
                    contentDescription = null,
                    tint = GoldAura500.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun NewRecordingWaveform(
    amplitude: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedAmplitude by animateFloatAsState(
        targetValue = if (isRecording) amplitude else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "Amplitude"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        // Glow Background
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(80.dp)
                .blur(40.dp)
                .alpha(0.15f * animatedAmplitude)
                .background(
                    Brush.radialGradient(
                        colors = listOf(GoldAura500, Color.Transparent)
                    )
                )
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val barCount = 40
            val barWidth = 3.dp.toPx()
            val spacing = 6.dp.toPx()
            val centerY = size.height / 2
            val centerX = size.width / 2

            for (i in 0 until barCount) {
                val distanceFromCenter = kotlin.math.abs(i - barCount / 2f) / (barCount / 2f)
                val scale = (1f - distanceFromCenter).coerceIn(0.1f, 1f)
                
                // Mirror effect
                val xOffset = (i - barCount / 2) * spacing
                
                // Add some randomness/noise to the amplitude for organic feel
                val noise = (kotlin.math.sin(i.toFloat() * 0.5f) * 0.2f) + 0.8f
                val height = (animatedAmplitude * size.height * scale * noise).coerceAtLeast(4.dp.toPx())

                drawLine(
                    color = GoldAura400.copy(alpha = (1f - distanceFromCenter).coerceIn(0.2f, 1f)),
                    start = Offset(centerX + xOffset, centerY - height / 2),
                    end = Offset(centerX + xOffset, centerY + height / 2),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
fun RecordingStatusSection(
    duration: String,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isRecording) {
                val infiniteTransition = rememberInfiniteTransition(label = "Dot")
                val dotAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = EaseInOutSine),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "Alpha"
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(dotAlpha)
                        .clip(CircleShape)
                        .background(Color(0xFFD64545))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Recording",
                    style = MaterialTheme.typography.labelMedium,
                    color = ReflectionWhite.copy(alpha = 0.7f),
                    letterSpacing = 1.sp
                )
            } else {
                Text(
                    text = "Paused",
                    style = MaterialTheme.typography.labelMedium,
                    color = ReflectionWhite.copy(alpha = 0.5f),
                    letterSpacing = 1.sp
                )
            }
        }

        Text(
            text = duration,
            style = MaterialTheme.typography.displayMedium.copy(
                fontWeight = FontWeight.Light,
                letterSpacing = 2.sp
            ),
            color = ReflectionWhite
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Recording your artifact...",
            style = MaterialTheme.typography.bodySmall,
            color = ReflectionWhite.copy(alpha = 0.3f),
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun NewRecordingControls(
    status: RecordingStatus,
    onRecordClick: () -> Unit,
    onPauseClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onFinishClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRecording = status == RecordingStatus.RECORDING
    val isPaused = status == RecordingStatus.PAUSED

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Delete Button
        IconButton(
            onClick = onDeleteClick,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.05f))
        ) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "Delete",
                tint = Color.White.copy(alpha = 0.5f)
            )
        }

        // Pause/Resume Button
        IconButton(
            onClick = onPauseClick,
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.05f))
        ) {
            Icon(
                imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = if (isPaused) "Resume" else "Pause",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(28.dp)
            )
        }

        // Main Record/Stop Button
        Box(contentAlignment = Alignment.Center) {
            val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (isRecording) 1.1f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "Scale"
            )

            Surface(
                onClick = onRecordClick,
                modifier = Modifier
                    .size(100.dp)
                    .scale(pulseScale),
                color = Color(0xFFD64545),
                shape = CircleShape,
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isRecording || isPaused) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = if (isRecording || isPaused) "Stop" else "Record",
                        tint = Color.White,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }
        }

        // Finish Button
        IconButton(
            onClick = onFinishClick,
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(GoldAura500.copy(alpha = 0.1f))
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Finish",
                tint = GoldAura500,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
