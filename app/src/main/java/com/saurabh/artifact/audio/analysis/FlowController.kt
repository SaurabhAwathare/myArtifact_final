package com.saurabh.artifact.audio.analysis

import com.saurabh.artifact.model.ReflectionQuestion

/**
 * Manages the runtime progression of a session's emotional arc.
 * Handles transitions and ensures smooth continuity between prompts.
 */
class FlowController(
    private val plan: SessionPromptPlan
) {
    private var currentIndex = 0

    val currentPrompt: ReflectionQuestion?
        get() = plan.prompts.getOrNull(currentIndex)
    
    val hasNext: Boolean
        get() = currentIndex < plan.prompts.size - 1

    /**
     * Advances to the next stage of the arc.
     * Returns a transition phrase to bridge the emotional shift.
     */
    fun next(totalSessionDurationSeconds: Long = 0): TransitionResult {
        if (!hasNext) return TransitionResult.End
        
        val previous = currentPrompt
        currentIndex++
        val next = currentPrompt!!
        
        val transition = getTransitionPhrase(previous, next, totalSessionDurationSeconds)
        return TransitionResult.Next(next, transition)
    }

    private fun getTransitionPhrase(
        from: ReflectionQuestion?, 
        to: ReflectionQuestion,
        sessionDuration: Long
    ): String? {
        if (from == null) return null

        // Goal 5: Safety check for early "Shift" (Stage 3 is index 2)
        if (currentIndex == 2 && sessionDuration < 45) {
            return "You're moving quickly. Let's take a beat with this next one..."
        }

        return when {
            to.depthLevel > from.depthLevel -> {
                listOf(
                    "Let's stay with that for a moment... maybe we can go a bit deeper.",
                    "That feels significant. If you're comfortable, let's explore that further.",
                    "I hear you. Let's see if there's more beneath that surface."
                ).random()
            }
            to.depthLevel < from.depthLevel -> {
                listOf(
                    "Thank you for sharing that. Let's take a breath and reflect on the bigger picture.",
                    "That was a deep dive. Let's bring it back to how this fits into your day.",
                    "Gently shifting gears... let's look at what we've discovered."
                ).random()
            }
            else -> {
                listOf(
                    "I see. Moving forward...",
                    "Thinking about that... what else comes to mind?",
                    "Continuing that thread..."
                ).random()
            }
        }
    }

    sealed class TransitionResult {
        data class Next(val question: ReflectionQuestion, val transition: String?) : TransitionResult()
        object End : TransitionResult()
    }
}
