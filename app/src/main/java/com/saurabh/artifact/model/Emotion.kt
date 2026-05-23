package com.saurabh.artifact.model

/**
 * Core emotional states for reflective framing.
 */
enum class Emotion(val label: String, val emoji: String) {
    HAPPY("Happy", "😊"),
    SAD("Sad", "😢"),
    ANGRY("Angry", "😠"),
    LONELY("Lonely", "🫂"),
    HOPEFUL("Hopeful", "✨"),
    CALM("Calm", "🌊"),
    ANXIOUS("Anxious", "😰"),
    CONFUSED("Confused", "🤔"),
    GRATEFUL("Grateful", "🙏"),
    OVERWHELMED("Overwhelmed", "🌊"),
    MOTIVATED("Motivated", "🔥"),
    NEUTRAL("Neutral", "😐")
}

data class EmotionResult(
    val emotion: Emotion,
    val confidence: Float
)
