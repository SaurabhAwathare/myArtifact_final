package com.saurabh.artifact.util

/**
 * Emotion-aware and safety-aware messaging engine.
 * Transforms raw emotional states into empathetic, non-intrusive notifications.
 */
object NotificationEngine {

    /**
     * Generates a personalized title and message based on the user's current context.
     * 
     * @param emotion The detected or selected dominant emotion.
     * @param safetyLevel Current safety assessment (LOW, MEDIUM, HIGH).
     * @return A Pair containing the notification Title and Body.
     */
    fun generateMessage(
        emotion: String?,
        safetyLevel: String
    ): Pair<String, String> {

        // 🚨 Safety override (highest priority)
        // If the system detects high risk, we shift to immediate support mode.
        if (safetyLevel == "HIGH") {
            return Pair(
                "You're not alone 🤍",
                "It's okay to feel this way. Take a breath. We're here with you."
            )
        }

        return when (emotion) {
            "Anxiety" -> Pair(
                "Take a gentle pause 🌿",
                "Would you like to express what's been on your mind?"
            )

            "Sadness" -> Pair(
                "You're allowed to feel this 💙",
                "Sometimes sharing even a little can help."
            )

            "Joy" -> Pair(
                "Hold onto this moment ✨",
                "Want to capture what made you feel this way?"
            )

            "Anger" -> Pair(
                "Let it out safely 🔥",
                "Your feelings matter—express them without judgment."
            )

            else -> Pair(
                "Check in with yourself 🌙",
                "How are you feeling right now?"
            )
        }
    }
}
