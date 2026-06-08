package com.saurabh.artifact.ui.splash

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian950
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun SplashUI() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        GoldAura500.copy(alpha = 0.05f),
                        Obsidian950
                    ),
                    center = Offset.Infinite,
                    radius = 2000f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(
                        color = GoldAura500,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Light
                    )) {
                        append("my")
                    }
                    withStyle(style = SpanStyle(
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )) {
                        append("Artifact")
                    }
                },
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 48.sp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "leave thoughts behind",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.5f)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp)
        ) {
            AnimatedWaveform()
        }
    }
}

@Composable
fun AnimatedWaveform() {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = Modifier.width(100.dp).height(40.dp)) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        for (i in 0..width.toInt() step 10) {
            val x = i.toFloat()
            val normalizedX = x / width
            val sine = sin(normalizedX * 2 * PI + phase).toFloat()
            val lineHeight = (sine * height / 2) + (height / 4)
            
            drawLine(
                color = GoldAura500.copy(alpha = 0.4f),
                start = Offset(x, centerY - lineHeight / 2),
                end = Offset(x, centerY + lineHeight / 2),
                strokeWidth = 4.dp.toPx()
            )
        }
    }
}
