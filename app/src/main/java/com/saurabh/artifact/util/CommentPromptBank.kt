package com.saurabh.artifact.util

import kotlin.random.Random

/**
 * A curated collection of reflective prompts to encourage meaningful responses.
 * Designed to move the conversation from opinion to shared understanding.
 */
object CommentPromptBank {

    private val prompts = listOf(
        "What part of this story stayed with you?",
        "How did hearing this change your perspective?",
        "A word of strength for this person?",
        "What was the most relatable moment for you?",
        "What question would you ask if you were sitting together?",
        "How did this reflection make you feel?",
        "Is there a memory of yours that this brought to light?",
        "What was the most courageous thing you heard?",
        "How can your response honor their vulnerability?",
        "What is one thing you learned from listening to this?"
    )

    /**
     * Returns a random reflective prompt.
     */
    fun getRandomPrompt(): String {
        return prompts[Random.nextInt(prompts.size)]
    }

    /**
     * Returns a stable prompt for a given ID (e.g., artifactId) to prevent changes during sessions.
     */
    fun getStablePrompt(id: String): String {
        val index = ((id.hashCode().toLong() and 0xFFFFFFFFL) % prompts.size).toInt()
        return prompts[index]
    }
}
