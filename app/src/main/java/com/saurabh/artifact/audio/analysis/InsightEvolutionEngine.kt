package com.saurabh.artifact.audio.analysis

import java.util.UUID

/**
 * Types of narrative evolution for insights.
 */
enum class InsightEvolutionType {
    NEW_THREAD,     // First time a theme or pattern appears
    CONTINUATION,   // Follows up on a recent theme
    SHIFT,          // A noticeable change from a previous pattern
    REINFORCEMENT   // A recurring pattern becoming more consistent
}

/**
 * An insight that is aware of its place in the user's longitudinal narrative.
 */
data class EvolvingInsight(
    val id: String = UUID.randomUUID().toString(),
    val rawInsight: ProgressInsight,
    val evolutionType: InsightEvolutionType,
    val narrativeSummary: String,
    val relatedInsightIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * The engine responsible for linking new insights to the existing narrative.
 */
object InsightEvolutionEngine {

    private const val MAX_HISTORY_SIZE = 10

    /**
     * Evolves a raw ProgressInsight by comparing it with the history of previous insights.
     */
    fun evolve(
        newInsight: ProgressInsight,
        history: List<EvolvingInsight>
    ): EvolvingInsight {
        val recent = history.takeLast(MAX_HISTORY_SIZE)
        
        // 1. Check for Reinforcement (Repeated TrendType in recent history)
        val sameTrendCount = recent.count { it.rawInsight.detectedTrend == newInsight.detectedTrend }
        if (sameTrendCount >= 2) {
            return createReinforcement(newInsight, recent)
        }

        // 2. Check for Continuation (Theme overlap)
        val overlappingInsight = recent.findLast { 
            (it.rawInsight.detectedTrend == TrendType.THEME_SHIFT) && (newInsight.detectedTrend == TrendType.THEME_SHIFT)
        }
        if (overlappingInsight != null) {
            return createContinuation(newInsight, overlappingInsight)
        }

        // 3. Check for Shift (Change in TrendType but same category like Depth)
        val previousDepthInsight = recent.findLast { it.rawInsight.detectedTrend == TrendType.DEPTH }
        if ((previousDepthInsight != null) && (newInsight.detectedTrend == TrendType.DEPTH)) {
            // Logic to detect if it's a shift or just another depth insight
            // For now, keep it simple
            return createShift(newInsight, previousDepthInsight)
        }

        // 4. Fallback to New Thread
        return EvolvingInsight(
            rawInsight = newInsight,
            evolutionType = InsightEvolutionType.NEW_THREAD,
            narrativeSummary = newInsight.summary
        )
    }

    private fun createReinforcement(newInsight: ProgressInsight, history: List<EvolvingInsight>): EvolvingInsight {
        val summary = when (newInsight.detectedTrend) {
            TrendType.DEPTH -> "You seem to be consistently finding more depth in your reflections lately."
            TrendType.CONSISTENCY -> "This steady flow in your reflections is becoming a regular pattern."
            TrendType.THEME_SHIFT -> "These themes seem to be staying with you across multiple days."
        }
        return EvolvingInsight(
            rawInsight = newInsight,
            evolutionType = InsightEvolutionType.REINFORCEMENT,
            narrativeSummary = summary,
            relatedInsightIds = history.map { it.id }
        )
    }

    private fun createContinuation(newInsight: ProgressInsight, previous: EvolvingInsight): EvolvingInsight {
        return EvolvingInsight(
            rawInsight = newInsight,
            evolutionType = InsightEvolutionType.CONTINUATION,
            narrativeSummary = "Returning to this again... it seems these thoughts are still unfolding for you.",
            relatedInsightIds = listOf(previous.id)
        )
    }

    private fun createShift(newInsight: ProgressInsight, previous: EvolvingInsight): EvolvingInsight {
        return EvolvingInsight(
            rawInsight = newInsight,
            evolutionType = InsightEvolutionType.SHIFT,
            narrativeSummary = "Something about this feels a bit different from your previous reflections.",
            relatedInsightIds = listOf(previous.id)
        )
    }
}
