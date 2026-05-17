package com.saurabh.artifact.ui.recording.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian950
import kotlin.random.Random

@Composable
fun RecordingAtmosphere() {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedGradientBackground()
        AmbientParticleSystem()
    }
}

@Composable
fun AnimatedGradientBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "GradientAnimation")
    
    val color1 by infiniteTransition.animateColor(
        initialValue = Color(0xFF050505), // Deep Black
        targetValue = Color(0xFF1A120B), // Very Warm Brown
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Color1"
    )

    val color2 by infiniteTransition.animateColor(
        initialValue = Color(0xFF0F0B08), // Dark Brown
        targetValue = Color(0xFF050505), // Black
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Color2"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.02f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(color1, color2)
                )
            )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(glowAlpha)
            .background(
                Brush.radialGradient(
                    colors = listOf(GoldAura500.copy(alpha = 0.4f), Color.Transparent),
                    center = Offset.Unspecified,
                    radius = 1500f
                )
            )
    )
}

@Composable
fun AmbientParticleSystem() {
    val particles = remember {
        List(15) {
            Particle(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                size = Random.nextFloat() * 4f + 2f,
                alpha = Random.nextFloat() * 0.3f + 0.1f,
                speed = Random.nextFloat() * 0.0005f + 0.0002f
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "Particles")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Time"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        particles.forEach { particle ->
            val currentY = (particle.y - (time * particle.speed * 100)) % 1f
            val finalY = if (currentY < 0) currentY + 1f else currentY
            
            drawCircle(
                color = GoldAura500,
                radius = particle.size,
                center = Offset(particle.x * size.width, finalY * size.height),
                alpha = particle.alpha * (1f - kotlin.math.abs(0.5f - finalY) * 2f)
            )
        }
    }
}

private data class Particle(
    val x: Float,
    val y: Float,
    val size: Float,
    val alpha: Float,
    val speed: Float
)
