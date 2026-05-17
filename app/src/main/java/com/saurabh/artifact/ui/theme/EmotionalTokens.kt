package com.saurabh.artifact.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Artifact Emotional Design Tokens.
 * 
 * Defines the "physics" of the emotional atmosphere.
 */
@Immutable
data class EmotionalTokens(
    val blurExtraLight: Dp = 4.dp,
    val blurLight: Dp = 8.dp,
    val blurMedium: Dp = 16.dp,
    val blurHeavy: Dp = 32.dp,
    val blurDeep: Dp = 64.dp,
    
    val glowSmall: Float = 0.2f,
    val glowMedium: Float = 0.5f,
    val glowStrong: Float = 0.8f,
    
    val breathingRateSlow: Int = 3000,
    val breathingRateNormal: Int = 2000,
    val breathingRateFast: Int = 1000,
    
    val surfaceOpacityLow: Float = 0.12f,
    val surfaceOpacityMedium: Float = 0.4f,
    val surfaceOpacityHigh: Float = 0.8f
)

val LocalEmotionalTokens = staticCompositionLocalOf { EmotionalTokens() }
