package com.saurabh.artifact.util

/**
 * Maps the 14 internal emotional states to the 8 primary UI filter categories.
 * Ensures all reachable content is discoverable while maintaining high-precision data.
 */
object EmotionCategoryMapper {

    /**
     * Returns a list of related emotions for a given UI filter chip.
     * This expansion allows "Happy" to discover "Hopeful" and "Grateful" content.
     */
    fun getRelatedEmotions(uiCategory: String): List<String> {
        return when (uiCategory) {
            "Happy" -> listOf("Happy", "Hopeful", "Grateful", "Motivated")
            "Motivated" -> listOf("Motivated", "Happy", "Hopeful")
            "Sad" -> listOf("Sad", "Lonely")
            "Lonely" -> listOf("Lonely", "Sad")
            "Anxious" -> listOf("Anxious", "Angry", "Overwhelmed")
            "Angry" -> listOf("Angry", "Anxious")
            "Neutral" -> listOf("Neutral", "Calm", "Confused", "Unclear")
            "Mixed" -> listOf("Mixed")
            else -> listOf(uiCategory)
        }
    }

    /**
     * Determines which UI category a specific internal emotion belongs to.
     * Useful for labeling or UI anchoring.
     */
    fun getCategoryForEmotion(internalEmotion: String): String {
        return when (internalEmotion) {
            "Hopeful", "Grateful", "Motivated" -> "Happy"
            "Lonely" -> "Sad"
            "Overwhelmed", "Angry" -> "Anxious"
            "Calm", "Confused", "Unclear" -> "Neutral"
            else -> internalEmotion
        }
    }
}
