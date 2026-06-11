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
import kotlin.math.PI
import kotlin.math.cos

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
        "happy" -> EmotionalThemeColors(Color(0xFFFFEB3B), Color(0xFFFF9800))
        "sad" -> EmotionalThemeColors(Color(0xFF2196F3), Color(0xFF3F51B5))
        "angry" -> EmotionalThemeColors(Color(0xFFF44336), Color(0xFFB71C1C))
        "anxious" -> EmotionalThemeColors(Color(0xFFFFC107), Color(0xFF795548))
        "lonely" -> EmotionalThemeColors(Color(0xFF9E9E9E), Color(0xFF212121))
        "motivated" -> EmotionalThemeColors(Color(0xFFFF5722), Color(0xFFFFEB3B))
        "grateful" -> EmotionalThemeColors(Color(0xFFE91E63), Color(0xFFFF80AB))
        "overwhelmed" -> EmotionalThemeColors(Color(0xFF673AB7), Color(0xFF00BCD4))
        "confused" -> EmotionalThemeColors(Color(0xFFCDDC39), Color(0xFF4CAF50))
        "mixed" -> EmotionalThemeColors(Color(0xFF9C27B0), Color(0xFFFF9800))
        "unclear" -> EmotionalThemeColors(Color(0xFF9E9E9E), Color(0xFF607D8B))
        "neutral" -> EmotionalThemeColors(Obsidian700, Obsidian900)
        else -> EmotionalThemeColors(GoldAura400, Obsidian900) // Default: Reflective-lite
    }
}


private val SineEaseInOut = Easing { fraction ->
    -(cos(PI * fraction).toFloat() - 1f) / 2f
}
