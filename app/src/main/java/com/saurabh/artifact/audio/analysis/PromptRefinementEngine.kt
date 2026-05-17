package com.saurabh.artifact.audio.analysis

import com.saurabh.artifact.model.ReflectionQuestion

/**
 * Emotional State: Detected from amplitude trends and silence patterns.
 */
enum class EmotionalState {
    CALM,
    ENGAGED,
    OVERWHELMED,
    HESITANT
}

/**
 * Reflection Mode: Determines the goal of the current session phase.
 */
enum class ReflectionMode {
    EXPRESS,   // Venting, letting it out
    SHIFT,     // Emotional redirection/stabilization
    GROW       // Deeper insight
}

/**
 * Senior Cognitive UX Strategy: "Companion-Aware Prompt Experience"
 * 
 * Evolved from a "Smart Prompt Generator" to a "Companion" that adapts 
 * to user readiness, emotional context, and timing.
 */
object PromptRefinementEngine {

    private val softPrefixes = listOf(
        "If it feels right...",
        "Only if you want to...",
        "You could stay with this a bit longer...",
        "Whenever you're ready...",
        "Take your time with this..."
    )

    /**
     * Refines a prompt based on multi-dimensional context.
     * Use remember(prompt.id, depth, emotion, timing, mode) in Compose.
     */
    fun refine(
        prompt: ReflectionQuestion,
        depth: DepthState,
        emotion: EmotionalState = EmotionalState.CALM,
        timing: TimingState = TimingState.FLOW,
        mode: ReflectionMode = ReflectionMode.EXPRESS
    ): String {
        val baseText = transformToInvitation(prompt.text)
        
        // 1. Core refinement based on mode and emotion
        var refined = when (mode) {
            ReflectionMode.EXPRESS -> applyExpressLogic(baseText, emotion)
            ReflectionMode.SHIFT -> applyShiftLogic(baseText, emotion)
            ReflectionMode.GROW -> applyGrowLogic(baseText, emotion, depth, prompt.id)
        }

        // 2. Adjust for Timing (Flow awareness)
        refined = when (timing) {
            TimingState.STUCK -> "Take your time... maybe start with what feels easiest to say. $refined"
            TimingState.CLOSING -> "You can pause here, or stay with it a little longer if you want. $refined"
            else -> refined // FLOW and PROCESSING use the refined text as is
        }

        // 3. Remove Hidden Pressure: Ensure no demanding language remains
        refined = removePressure(refined)

        return refined
    }

    /**
     * EXPRESS Mode: Focus on safety and release.
     */
    private fun applyExpressLogic(text: String, emotion: EmotionalState): String {
        return when (emotion) {
            EmotionalState.OVERWHELMED -> "You can just let it out... $text"
            EmotionalState.HESITANT -> "No rush at all. $text"
            else -> "I'm here to listen. $text"
        }
    }

    /**
     * SHIFT Mode: Focus on stabilization and perspective.
     */
    private fun applyShiftLogic(text: String, emotion: EmotionalState): String {
        return when (emotion) {
            EmotionalState.OVERWHELMED -> "Let's find a bit of steady ground. $text"
            else -> "Noticing how that shifts things... $text"
        }
    }

    /**
     * GROW Mode: Focus on depth and curiosity.
     */
    private fun applyGrowLogic(text: String, emotion: EmotionalState, depth: DepthState, promptId: String): String {
        val prefix = if (depth == DepthState.DEEP && emotion == EmotionalState.ENGAGED) {
            "Since you're exploring this deeply... "
        } else {
            val deterministicIndex = kotlin.math.abs(promptId.hashCode()) % softPrefixes.size
            "${softPrefixes[deterministicIndex]} "
        }
        return "$prefix$text"
    }

    /**
     * Core Principle: Transform "Questions to answer" -> "Invitations to explore"
     */
    private fun transformToInvitation(text: String): String {
        var transformed = text
        
        if (transformed.startsWith("What happened")) {
            transformed = transformed.replaceFirst("What happened", "What's been sitting with you")
        }
        
        return transformed
            .replace("How do you", "How does it feel to")
            .replace("Why did", "I wonder what led to")
            .replace("Describe", "Notice")
            .replace("Identify", "Gently look for")
            .replace("?", "...")
            .replace(Regex("^What "), "I'm curious about what ")
            .trim()
    }

    /**
     * Safety Net: Replaces demanding phrases with supportive alternatives.
     */
    private fun removePressure(text: String): String {
        return text
            .replace("There might be more here...", "If it feels right, you might explore...")
            .replace("You should", "You could")
            .replace("Tell me", "Feel free to share")
            .replace("Explain", "Explore")
    }
}
