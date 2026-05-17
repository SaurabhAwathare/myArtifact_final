package com.saurabh.artifact.model

object ReflectionPromptProvider {
    private val prompts = listOf(
        ReflectionPrompt(
            id = "default_1",
            question = "What's one thing you're grateful for today?",
            category = PromptCategory.GRATITUDE,
            tone = EmotionalTone.REFLECTIVE
        ),
        ReflectionPrompt(
            id = "default_2",
            question = "How are you feeling in this exact moment?",
            category = PromptCategory.SELF_REFLECTION,
            tone = EmotionalTone.GENTLE
        ),
        ReflectionPrompt(
            id = "default_3",
            question = "What is one small win you had today?",
            category = PromptCategory.GRATITUDE,
            tone = EmotionalTone.GENTLE
        )
    )

    fun getRandomPrompt(): ReflectionPrompt {
        return prompts.random()
    }

    fun getAllPrompts(): List<ReflectionPrompt> {
        return prompts
    }
}
