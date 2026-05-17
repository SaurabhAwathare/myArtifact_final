package com.saurabh.artifact.audio.analysis

import com.saurabh.artifact.model.ReflectionQuestion

data class ReflectionMemory(
    val recentQuestionIds: List<String> = emptyList(),
    val themeFrequency: Map<String, Int> = emptyMap(),
    val averageDepthPreference: Float = 2.0f,
    val totalSessions: Int = 0,
    val lastSessionTimestamp: Long = 0L,
    val history: List<EmotionalSnapshot> = emptyList(),
    val pendingInsight: DelayedInsight? = null,
    val insightHistory: List<EvolvingInsight> = emptyList()
) {
    companion object {
        fun updatedMemory(
            currentMemory: ReflectionMemory,
            usedQuestions: List<ReflectionQuestion>,
            finalDepthReached: Int,
            energyPattern: EnergyPattern = EnergyPattern.UNCLEAR,
            durationSeconds: Long = 0
        ): ReflectionMemory {
            val newRecent = (usedQuestions.map { it.id } + currentMemory.recentQuestionIds)
                .distinct()
                .take(30)

            val newThemeFreq = currentMemory.themeFrequency.toMutableMap()
            usedQuestions.flatMap { it.tags }.forEach { tag ->
                newThemeFreq[tag] = (newThemeFreq[tag] ?: 0) + 1
            }

            val newDepthPref = if (currentMemory.totalSessions == 0) {
                finalDepthReached.toFloat()
            } else {
                (currentMemory.averageDepthPreference * 0.7f) + (finalDepthReached * 0.3f)
            }

            val snapshot = EmotionalSnapshot(
                timestamp = System.currentTimeMillis(),
                themes = usedQuestions.flatMap { it.tags }.distinct(),
                maxDepthReached = finalDepthReached,
                energyPattern = energyPattern,
                durationSeconds = durationSeconds
            )

            return currentMemory.copy(
                recentQuestionIds = newRecent,
                themeFrequency = newThemeFreq,
                averageDepthPreference = newDepthPref,
                totalSessions = currentMemory.totalSessions + 1,
                lastSessionTimestamp = System.currentTimeMillis(),
                history = (currentMemory.history + snapshot).takeLast(20),
                pendingInsight = currentMemory.pendingInsight
            )
        }
    }
}
