package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

@Serializable
data class ReflectionPrompt(
    val id: String,
    val category: PromptCategory,
    val question: String,
    val mood: String? = null,
    val tone: EmotionalTone = EmotionalTone.REFLECTIVE,
    val isFavorite: Boolean = false,
    val usageCount: Int = 0
)

@Serializable
enum class PromptCategory(val displayName: String) {
    SELF_REFLECTION("Self-Reflection"),
    RELATIONSHIPS("Relationships"),
    ANXIETY("Anxiety"),
    LONELINESS("Loneliness"),
    PURPOSE("Purpose"),
    GROWTH("Growth"),
    CREATIVITY("Creativity"),
    GRATITUDE("Gratitude"),
    COMFORT("Comfort"),
    CHECK_IN("Check-in"),
    IMAGINATION("Imagination"),
    FUN("Fun"),
    AI_GUIDED("AI Guided"),
    GENERAL("General")
}
