package com.saurabh.artifact.ui.player.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.saurabh.artifact.ui.theme.*

/**
 * EmotionalBackground - A dynamic, atmospheric background engine.
 * Slowly shifts colors and "breathes" based on the artifact's emotion.
 */
@Composable
fun EmotionalBackground(
    emotion: String,
    modifier: Modifier = Modifier
) {
    val theme = getEmotionalTheme(emotion)

    // 1. Slow Color Transitions
    val topColor by animateColorAsState(
        targetValue = theme.topColor,
        animationSpec = tween(durationMillis = 3000, easing = LinearOutSlowInEasing),
        label = "TopColorTransition"
    )
    val bottomColor by animateColorAsState(
        targetValue = theme.bottomColor,
        animationSpec = tween(durationMillis = 3000, easing = LinearOutSlowInEasing),
        label = "BottomColorTransition"
    )

    // 2. Breathing Intensity Animation
    val infiniteTransition = rememberInfiniteTransition(label = "AtmosphericBreathing")
    val intensity by infiniteTransition.animateFloat(
        initialValue = 0.03f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = SineEaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BreathingIntensity"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Obsidian950) // Base Layer
    ) {
        // Atmospheric Gradient Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            topColor.copy(alpha = intensity),
                            bottomColor.copy(alpha = intensity * 1.5f),
                            Obsidian950
                        )
                    )
                )
        )
    }
}

private data class EmotionalThemeColors(
    val topColor: Color,
    val bottomColor: Color
)

private fun getEmotionalTheme(emotion: String): EmotionalThemeColors {
    return when (emotion.lowercase()) {
        "reflective" -> EmotionalThemeColors(GoldAura400, Obsidian800)
        "heavy" -> EmotionalThemeColors(DeepMeditation, Obsidian950)
        "hopeful" -> EmotionalThemeColors(GoldAura500, TrustMoss)
        "calm" -> EmotionalThemeColors(TrustMoss, Obsidian900)
        else -> EmotionalThemeColors(GoldAura400, Obsidian900) // Default: Reflective-lite
    }
}

private val SineEaseInOut = Easing { fraction ->
    -(Math.cos(Math.PI * fraction).toFloat() - 1f) / 2f
}
