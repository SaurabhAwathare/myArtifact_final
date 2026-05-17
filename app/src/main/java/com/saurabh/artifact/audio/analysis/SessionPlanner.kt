package com.saurabh.artifact.audio.analysis

import com.saurabh.artifact.model.ReflectionQuestion

/**
 * Represents a structured sequence of prompts for a single session.
 * Prevents mid-session randomness by pre-calculating the emotional arc.
 */
data class SessionPromptPlan(
    val prompts: List<ReflectionQuestion>,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Orchestrates the emotional journey of a session.
 * Maps the 4-stage arc: Grounding -> Exploration -> Shift -> Integration.
 */
class SessionPlanner(
    private val memory: ReflectionMemory,
    private val allQuestions: List<ReflectionQuestion>
) {
    fun plan(dominantEmotion: String?, targetDepth: IntRange): SessionPromptPlan {
        val averageDuration = memory.history.map { it.durationSeconds }.average()
        val isLongSessionUser = averageDuration > 120

        // Stage 1: Grounding (Depth 1) - Low cognitive load entry
        val grounding = selectForStage(1, 1..1, dominantEmotion)
        
        // Stage 2: Exploration (Target Depth) - Expansion and detail
        // Add an extra prompt if the user typically has longer sessions to allow for more depth
        val explorationCount = if (isLongSessionUser) 2 else 1
        val exploration = selectForStage(explorationCount, targetDepth, dominantEmotion, exclude = grounding)
        
        // Stage 3: Shift/Perspective (Target Depth or +1) - Meaning and "unfinished thought"
        val shiftDepth = (targetDepth.last + 1).coerceAtMost(4)
        val shift = selectForStage(1, shiftDepth..shiftDepth, dominantEmotion, exclude = grounding + exploration)
        
        // Stage 4: Integration/Closing (Depth 1-2, but reflective) - Concise closing
        val integration = selectForStage(1, 1..2, dominantEmotion, exclude = grounding + exploration + shift)

        val plan = (grounding + exploration + shift + integration).distinctBy { it.id }
        
        return SessionPromptPlan(
            prompts = plan,
            metadata = mapOf(
                "emotion_context" to (dominantEmotion ?: "neutral"),
                "planned_depth" to targetDepth.toString(),
                "session_type" to if (isLongSessionUser) "extended" else "standard"
            )
        )
    }

    private fun selectForStage(
        count: Int,
        depthRange: IntRange,
        emotion: String?,
        exclude: List<ReflectionQuestion> = emptyList()
    ): List<ReflectionQuestion> {
        val excludeIds = exclude.map { it.id } + memory.recentQuestionIds.take(15)
        
        return allQuestions.filter { 
            it.depthLevel in depthRange && it.id !in excludeIds 
        }.sortedByDescending { q ->
            var score = 0f
            // Theme continuity
            q.tags.forEach { tag ->
                score += (memory.themeFrequency[tag] ?: 0) * 0.5f
            }
            // Emotion matching (if tags contain emotion)
            if (emotion != null && q.tags.contains(emotion.lowercase())) {
                score += 2f
            }
            score
        }.take(count)
    }
}
