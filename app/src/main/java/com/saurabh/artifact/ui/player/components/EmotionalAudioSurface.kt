package com.saurabh.artifact.ui.player.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.theme.*
import kotlin.math.sin

/**
 * EmotionalAudioSurface - The core visual presence of the player.
 * A pulsing, breathing orb surrounded by an atmospheric glow that reacts to playback.
 */
@Composable
fun EmotionalAudioSurface(
    emotion: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val theme = getEmotionalTheme(emotion)
    
    val infiniteTransition = rememberInfiniteTransition(label = "OrbPulse")
    
    // Breathing animation for the core orb
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    // Slow rotation for the atmospheric glow
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "GlowRotation"
    )

    // Playback reactive intensity
    val playbackIntensity by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.3f,
        animationSpec = tween(durationMillis = 1000),
        label = "PlaybackIntensity"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(320.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = size.width * 0.35f
            
            // 1. Far Atmospheric Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        theme.topColor.copy(alpha = 0.15f * playbackIntensity),
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.width * 0.5f
                )
            )

            // 2. The Emotional Core (The Orb)
            val orbRadius = baseRadius * pulseScale
            
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        theme.topColor.copy(alpha = 0.8f * playbackIntensity),
                        theme.bottomColor.copy(alpha = 0.4f * playbackIntensity),
                        Color.Transparent
                    ),
                    center = center,
                    radius = orbRadius * 1.2f
                ),
                radius = orbRadius * 1.2f,
                center = center
            )

            // 3. Inner Detail (Soft Highlight)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.2f * playbackIntensity),
                        Color.Transparent
                    ),
                    center = center,
                    radius = orbRadius * 0.6f
                ),
                radius = orbRadius * 0.6f,
                center = center
            )
        }
    }
}

private data class PlayerEmotionalThemeColors(
    val topColor: Color,
    val bottomColor: Color
)

private fun getEmotionalTheme(emotion: String): PlayerEmotionalThemeColors {
    return when (emotion.lowercase()) {
        "reflective" -> PlayerEmotionalThemeColors(GoldAura400, Obsidian800)
        "heavy" -> PlayerEmotionalThemeColors(DeepMeditation, Obsidian950)
        "hopeful" -> PlayerEmotionalThemeColors(GoldAura500, TrustMoss)
        "calm" -> PlayerEmotionalThemeColors(TrustMoss, Obsidian900)
        "angry" -> PlayerEmotionalThemeColors(Color(0xFFE91E63), Color(0xFF310000))
        "lonely" -> PlayerEmotionalThemeColors(Color(0xFF90CAF9), Color(0xFF001021))
        else -> PlayerEmotionalThemeColors(GoldAura400, Obsidian900)
    }
}
