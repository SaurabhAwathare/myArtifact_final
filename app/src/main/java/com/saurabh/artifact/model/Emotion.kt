package com.saurabh.artifact.model

/**
 * Core emotional states supported by the Artifact intelligence system.
 */
enum class Emotion(val label: String, val emoji: String) {
    HAPPY("Happy", "😊"),
    SAD("Sad", "😢"),
    LONELY("Lonely", "🫂"),
    ANXIOUS("Anxious", "😰"),
    ANGRY("Angry", "😠"),
    MOTIVATED("Motivated", "✨"),
    NEUTRAL("Neutral", "😐")
}

data class EmotionResult(
    val emotion: Emotion,
    val confidence: Float
)
